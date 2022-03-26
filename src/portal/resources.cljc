(ns ^:no-doc portal.resources
  #?(:cljs (:require-macros portal.resources))
  #?(:clj (:require [clojure.java.io :as io])))

#?(:clj
   (defmacro inline [resource-name]
     (try
       (slurp (io/resource resource-name))
       (catch Exception e
         (println e)))))
