(defproject javelin "0.1.0-SNAPSHOT"
  :description "FIXME"
  :url "http://please.FIXME"
  :dependencies [[org.clojure/clojure       "1.8.0"]
                 [org.clojure/clojurescript "1.9.456"]
                 [org.clojure/core.async    "0.2.395"]
                 [io.nervous/cljs-lambda    "0.3.5"]
                 [adzerk/env "0.4.0"]]
  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-npm       "0.6.0"]
            [lein-doo       "0.1.7"]
            [io.nervous/lein-cljs-lambda "0.6.6"]]
  :npm {:dependencies [[source-map-support "0.4.0"]
                       [markdown-it        "8.3.1"]
                       [s3                 "4.4.0"]
                       [bluebird           "3.5.0"]]}
  :source-paths ["src"]
  :cljs-lambda
  {:defaults      {:role "arn:aws:iam::183162473255:role/cljs-lambda-default"}
   :resource-dirs ["static"]
   :functions
   [{:name   "work-magic"
     :invoke javelin.core/work-magic}]}
  :cljsbuild
  {:builds [{:id "javelin"
             :source-paths ["src"]
             :compiler {:output-to     "target/javelin/javelin.js"
                        :output-dir    "target/javelin"
                        :source-map    true
                        :target        :nodejs
                        :language-in   :ecmascript5
                        :optimizations :none}}
            {:id "javelin-test"
             :source-paths ["src" "test"]
             :compiler {:output-to     "target/javelin-test/javelin.js"
                        :output-dir    "target/javelin-test"
                        :target        :nodejs
                        :language-in   :ecmascript5
                        :optimizations :none
                        :main          javelin.test-runner}}]})
