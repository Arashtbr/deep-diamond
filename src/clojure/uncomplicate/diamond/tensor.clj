(ns uncomplicate.diamond.tensor
  (:require [uncomplicate.commons
             [core :refer [release let-release Info]]
             [utils :refer [dragan-says-ex cond-into]]]
            [uncomplicate.diamond.internal.protocols :as api]))

(def ^:dynamic *diamond-factory* nil)

(defmacro with-diamond
  "Dynamically binds a factory created by `factory-fn` with provided parameters
  `params`, and evaluates `body` in the context of that factory."

  [factory-fn params & body]
  `(binding [*diamond-factory* (~factory-fn ~@params)]
     (try ~@body
          (finally (release *diamond-factory*)))))

;; ====================== Public protocols =====================================

(defprotocol TensorDescriptor
  "Generic tensor descriptor."
  (shape [tz] "Tensor shape, as a vector ([2 3 4]).")
  (layout [tz] "Tensor layout, as a keyword (:nchw).")
  (data-type [tz] "Data type of tensor entries (:float)."))

(extend-type java.util.Collection
  TensorDescriptor
  (shape [this]
    (vec this))
  (data-type [_]
    nil)
  (layout [_]
    nil))

(extend-type java.lang.Number
  TensorDescriptor
  (shape [this]
    [this])
  (data-type [_]
    nil)
  (layout [_]
    [1]))

(extend-type clojure.lang.IPersistentVector
  TensorDescriptor
  (shape [this]
    this)
  (data-type [_]
    nil)
  (layout [_]
    nil))

(extend-type java.util.Map
  TensorDescriptor
  (shape [this]
    (get this :shape []))
  (data-type [this]
    (get this :data-type))
  (layout [this]
    (or (get this :layout) (get this :format))))

(defprotocol TensorContainer
  "An object that can be viewed as a tensor."
  (view-tz [this] [this sub] "View the object's underlying data through a (sub)tensor"))

(defprotocol Revert
  "A directed transformation whose direction can be reverted."
  (revert [this] "Revert the direction of the transformation"))

(defprotocol Transfer
  "An operation that transfers input to output."
  (input [this] "The input tensor of the Transfer.")
  (output [this] "The output tensor of the Transfer."))

(defprotocol ConnectorCreator
  "An object that can create a connection and provide its state in another shape,
  or gets its state from an object with another shape."
  (connector [in out]))

;; =============================================================================

(defrecord TensorDescriptorImpl [shape data-type layout]
  Info
  (info [this]
    {:class (class this)
     :device :cpu
     :shape shape
     :data-type data-type
     :layout layout})
  TensorDescriptor
  (shape [_]
    shape)
  (data-type [_]
    data-type)
  (layout [_]
    layout)
  ConnectorCreator
  (connector [in-desc out]
    (connector (api/create-tensor-desc (api/diamond-factory out) in-desc) out)))

(defn desc
  "Creates a general, technology-agnostic, tensor descriptor.

  The required parameter `shape` is a vector of tensor dimensions.
  Optionally, `type` and `layout` can be provided as keywords, that
  are later transformed to appropriate internal implementations
  supported by specific backend. Alternatively, `layout` might be
  provided as a vector of offsets.

  Examples:

  (desc [2 3 2]) => {:shape [2 3 2], :data-type nil, :layout nil}
  (desc [2 3] :nc) => {:shape [2 3], :data-type nil, :layout :nc}
  (desc [2 3 2 4] :nhwc) => {:shape [2 3 2 4], :data-type nil, :layout :nhwc}
  "
  ([shape type layout]
   (if (and (vector? shape)
            (or (keyword? type) (class? type) (nil? type))
            (or (and (vector? layout) (= (count shape) (count layout)))
                (and (keyword? layout) (= (count shape) (count (name layout))))
                (nil? layout)))
     (->TensorDescriptorImpl shape type layout)
     (dragan-says-ex "The requested descriptor would most likely be inconsistent."
                     {:shape shape :type type :layout layout :errors
                      (cond-into []
                                 (not (vector? shape)) "shape is not a vector"
                                 (not (or (keyword? type) (symbol? type) (nil? type)))
                                 "type is not a keyword, class, or nil"
                                 (not (or (vector? layout) (keyword? layout) (nil? layout)))
                                 "layout is not a keyword, vector, or nil"
                                 (and (vector? layout) (not= (count shape) (count layout)))
                                 "shape and layout must have the same length"
                                 (and (keyword? layout) (not= (count shape) (count (name layout))))
                                 "shape and layout must have the same length")})))
  ([shape layout]
   (desc shape nil layout))
  ([tdesc]
   (desc (shape tdesc) (data-type tdesc) (layout tdesc))))

(defn tensor
  "Creates a technology-specific tensor.

  The backend is determined by `tz-factory`, while the structure
  is provided either from a general or technology specific descriptor
  `desc`, or from directly provided descriptor parameters `shape`,
  `type`, and `layout`. If `tz-factory` is not provided, it is
  taken from the `*diamond-factory*` binding, which is by default
  [[internal/dnnl]].

  Examples:

  (tensor {:shape [2 3] :data-type :float :layout :nc})
  =>
  {:shape [2 3], :data-type :float, :layout [3 1]}
  [   0.00    0.00    0.00    ⋯      0.00    0.00 ]

  (tensor (desc [2 3] :float :nc))
  =>
  {:shape [2 3], :data-type :float, :layout [3 1]}
  [   0.00    0.00    0.00    ⋯      0.00    0.00 ]

  (tensor [2 3] :float :nc)
  =>
  {:shape [2 3], :data-type :float, :layout [3 1]}
  [   0.00    0.00    0.00    ⋯      0.00    0.00 ]
  "
  ([tz-factory shape type layout]
   (if (sequential? shape)
     (let [fact (api/diamond-factory tz-factory)]
       (api/create-tensor fact (api/create-tensor-desc fact shape type layout) true))
     (dragan-says-ex "Tensor shape has to be sequential." {:shape (class shape)})))
  ([shape type layout]
   (tensor *diamond-factory* shape type layout))
  ([tz-factory desc]
   (let [fact (api/diamond-factory tz-factory)]
     (api/create-tensor fact (api/create-tensor-desc fact desc) true)))
  ([desc]
   (tensor *diamond-factory* desc)))

(defn offset! [tz ^long n]
  (api/offset tz n))

(defn transformer
  "Creates a function optimized for transferring data from tensor
  `x` to tensor `y`.

  Example:

  (def t1 (tensor [2 3] :float :nc))
  (transfer! (range) t1)
  (def t2 (tensor [3 2] :float :cn))
  (def tr-1-2 (transformer t1 t2))

  (tr-1-2)
  =>
  {:shape [2 3], :data-type :float, :layout [1 2]}
  [   0.00    3.00    1.00    ⋯      2.00    5.00 ]

  t2
  =>
  {:shape [2 3], :data-type :float, :layout [1 2]}
  [   0.00    3.00    1.00    ⋯      2.00    5.00 ]
  "
  [x y]
  (api/create-transformer (api/diamond-factory x) x y))

(defn batcher
  "Creates a function that can transfer mini-batches of tensor `x`
  to tensor `y`.

  Useful for, but not limited to, cycling through mini-batches
  during stochastic gradient descent. Assumes that the input data has
  already been shuffled (but does not depend on it technically).

  Example:

  (def t1 (tensor [2 3] :float :nc))
  (def t2 (tensor [1 3] :float :cn))
  (transfer! (range) t1)
  (def bat (batcher t1 t2))

  (bat)
  =>
  {:shape [1 3], :data-type :float, :layout [1 1]}
  [   0.00    1.00    2.00 ]

  (bat 1)
  =>
  {:shape [1 3], :data-type :float, :layout [1 1]}
  [   3.00    4.00    5.00 ]
  "
  ([x y]
   (batcher x y (min (long ((shape x) 0)) (long ((shape y) 0)))))
  ([x y ^long mb-size]
   (api/create-batcher (api/diamond-factory x) x y mb-size)))

(defn shuffler
  "Creates a function that can transfer randomly selected columns
  of tensor `x` to tensor `y` in mini-batches.

  Useful for, but not limited to, cycling  through mini-batches during
  stochastic gradient descent. The difference from batcher is that it
  randomly selects the grouping of columns. Shuffler is generally
  slower than plain batcher.

  Example:

  (def t1 (tensor [2 3] :float :nc))
  (def t2 (tensor [1 3] :float :cn))
  (transfer! (range) t1)
  (def bat (batcher t1 t2))

  (bat)
  =>
  {:shape [1 3], :data-type :float, :layout [1 1]}
  [   0.00    1.00    2.00 ]

  (bat 1)
  =>
  {:shape [1 3], :data-type :float, :layout [1 1]}
  [   3.00    4.00    5.00 ]
  "
  [x y]
  (api/create-shuffler (api/diamond-factory x) x y))
