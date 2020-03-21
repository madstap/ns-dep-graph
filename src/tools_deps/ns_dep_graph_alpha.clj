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

(defn print-paths [paths]
  (println "Paths:" (pr-str (vec paths))))

(defn -main [& args]
  (let [{{:keys [paths verbose]} :options} (cli/parse-opts args cli-opts)
        ps                                 (or paths (classpath))]
    (when verbose (print-paths ps))
    (lein.ns-dep/ns-dep-graph {:source-paths ps})
    (shutdown-agents)))
