(ns tools-deps.ns-dep-graph-alpha
  (:require
   [leiningen.ns-dep-graph :as lein.ns-dep]
   [clojure.tools.cli :as cli]
   [clojure.string :as str]))

(def cli-opts
  [["-p" "--paths PATHS" "One or more directories separated by :"
    :parse-fn #(str/split % #":")]])

;; TODO: Read from deps.edn
;; FIXME: https://github.com/hilverd/lein-ns-dep-graph/issues/3
(defn -main [& args]
  (let [{{:keys [paths]} :options} (cli/parse-opts args cli-opts)]
    (lein.ns-dep/ns-dep-graph {:source-paths paths})
    (shutdown-agents)))

(comment

  )
