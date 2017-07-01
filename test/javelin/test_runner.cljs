(ns javelin.test-runner
 (:require [doo.runner :refer-macros [doo-tests]]
           [javelin.core-test]
           [cljs.nodejs :as nodejs]))

(try
  (.install (nodejs/require "source-map-support"))
  (catch :default _))

(doo-tests
 'javelin.core-test)
