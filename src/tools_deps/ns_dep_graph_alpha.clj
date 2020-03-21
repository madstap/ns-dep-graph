(ns tools-deps.ns-dep-graph-alpha
  (:require
   [leiningen.ns-dep-graph :as lein.ns-dep]
   [clojure.tools.cli :as cli]
   [clojure.string :as str]))

(def cli-opts
  [["-p" "--paths PATHS" "One or more directories separated by :"
    :parse-fn #(str/split % #":")]
   ["-v" "--verbose" "Print debug info"]])

(defn jar? [s]
  (str/ends-with? s ".jar"))

(defn gitlib? [s]
  (str/includes? s ".gitlibs"))

(defn classpath []
  (let [paths (-> (System/getProperty "java.class.path") (str/split #":"))]
    (->> paths (remove jar?) (remove gitlib?))))

;; FIXME: https://github.com/hilverd/lein-ns-dep-graph/issues/3
(defn -main [& args]
  (let [{{:keys [paths verbose]} :options} (cli/parse-opts args cli-opts)]
    (when verbose (println "Inferred paths:" (pr-str (classpath))))
    (lein.ns-dep/ns-dep-graph {:source-paths (or paths (classpath))})
    (shutdown-agents)))

(comment

  )
