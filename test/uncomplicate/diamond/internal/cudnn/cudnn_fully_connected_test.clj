;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.diamond.internal.cudnn.cudnn-fully-connected-test
  (:require [uncomplicate.commons [core :refer [with-release]]]
            [uncomplicate.diamond.dnn-test :refer :all]
            [uncomplicate.diamond.internal.cudnn.factory :refer [cudnn-factory]]))

(with-release [fact (cudnn-factory)]
  (test-sum fact)
  (test-activation fact)
  (test-fully-connected-inference fact)
  (test-fully-connected-transfer fact)
  (test-fully-connected-training fact)
  (test-fully-connected-layer-1 fact)
  (test-fully-connected-layer-2 fact)
  (test-sequential-network-linear fact)
  (test-sequential-network-detailed fact)
  (test-quadratic-cost fact)
  (test-sequential-network-sigmoid fact)
  (test-gradient-descent fact)
  (test-stochastic-gradient-descent fact)
  (test-adam-gradient-descent fact))
