(ns tools-deps.ns-dep-graph-alpha
  (:require
   [clojure.java.shell :as sh]
   [clojure.java.io :as io]
   [leiningen.ns-dep-graph :as lein.ns-dep]
   [clojure.pprint :refer [pprint]]
   [clojure.tools.namespace.find :as ns.find]
   [clojure.tools.namespace.track :as ns.track]
   [clojure.tools.namespace.file :as ns.file]
   [clojure.tools.namespace.dependency :as ns.dep]
   [clojure.tools.namespace.parse :as ns.parse]
   [clojure.tools.cli :as cli]
   [clojure.string :as str]
   [rhizome.viz :as viz]
   [rhizome.dot :as dot])
  (:import (java.util.jar JarFile)
           (java.util.regex Pattern)
           (javax.imageio ImageIO)
           (java.io File)))

(defn index-by
  [f coll]
  (into {} (map (juxt f identity)) coll))

(def this-ns (ns-name *ns*))

(defn jar? [{:keys [path]}]
  (str/ends-with? path ".jar"))

(defn gitlib? [{:keys [path]}]
  (str/includes? path ".gitlibs"))

(def project?
  (complement (some-fn jar? gitlib?)))

(defn java-classpath []
  (->> (-> (System/getProperty "java.class.path") (str/split #":"))
       (map (fn [p] {:path p}))))

(defn project-paths [] (filter project? (java-classpath)))
(defn jar-paths [] (filter jar? (java-classpath)))
(defn gitlib-paths [] (filter gitlib? (java-classpath)))

(def platform->opts
  {:clj ns.find/clj
   :cljs ns.find/cljs})

#_
(->> (project-paths)
     (mapcat (fn [{:keys [path] :as m}]
               (->> (ns.find/find-ns-decls-in-dir (io/file path) popts)
                    (map #(assoc m :decl %)))))
     (map #(assoc % :project? true, :source :project)))

(defn decl->aliases [decl]
  (->> decl
       (filter sequential?)
       (filter #(= :require (first %)))
       (mapcat rest)
       ;; TODO: Warn if there is more than one alias for a given namespace.
       (map #(if (symbol? %)
               [% nil]
               (let [[name & more :as xs] %]
                 [name (second (drop-while (complement #{:as}) more))])))
       (into {})))

(defn parse-decl [{:keys [decl] :as m}]
  (-> m (assoc :name (ns.parse/name-from-ns-decl decl)
               :aliases (decl->aliases decl))))

(defn project-files [platform]
  (->> (project-paths)
       (mapcat (fn [{:keys [path] :as dir}]
                 (->> (ns.find/find-sources-in-dir (io/file path) (platform->opts platform))
                      (map #(assoc dir
                                   :file %
                                   :decl (ns.file/read-file-ns-decl %)
                                   :project? true,
                                   :source :project)))))
       (map parse-decl)))

(defn project-tracker [platform]
  (->> (project-files platform)
       (map :file)
       (ns.file/add-files {})))

(defn project-dep-graph [platform]
  (::ns.track/deps (project-tracker platform)))

(defn split-path [path]
  (str/split path (Pattern/compile (Pattern/quote (File/separator)))))

(defn path->libspec [path]
  (let [[_ version artifact group] (reverse (split-path path))]
    [(symbol group artifact) version]))

(defn add-libspec [{:keys [path] :as m}]
  (assoc m :libspec (path->libspec path)))

(defn jar-files [platform]
  (->> (jar-paths)
       (map add-libspec)
       (mapcat (fn [{:keys [path] :as m}]
                 (->> (ns.find/find-ns-decls-in-jarfile (JarFile. path) (platform->opts platform))
                      (map #(assoc m
                                   :decl %
                                   :project? false
                                   :source :jar)))))
       (map parse-decl)))

(defn all-sources [platform]
  ;; TODO: Gitlibs
  ;; TODO: Local libs
  ;;       (they're probably included in project files)
  ;;       Is there even a way to know which are which of project files and local deps?
  ;;       To read deps.edn I'd have to know about the aliases, can I know that?
  ;;
  ;;       I can infer whether something is a local dep or a project path as
  ;;       long as there's no dirs that are project paths in one alias and local
  ;;       dep in another alias, which doesn't seem very likely to be a problem.
  ;;
  ;;       I need to read and merge the whole chain of deps.edn files currently
  ;;       in play, can I know that?
  ;;
  ;;       What are the tradeoffs between getting the classpath from input
  ;;       flags/aliases and the tools.deps library or from the current classpath.
  ;;;      The latter being what we're currently doing.
  (concat (project-files platform)
          (jar-files platform)))

(defn ns-tracker [files]
  (->> files (map :path) (ns.file/add-files {})))

(defn p< [x]
  (doto x println))

(defn gen-dot [{:keys [deps foreign platform] :as options}]
  (let [depg (project-dep-graph platform)
        node->data (index-by :name (all-sources platform))
        include? #(or foreign (:project? (node->data %)))]
    (dot/graph->dot
     (filter include? (ns.dep/nodes depg))
     (fn [dep]
       (filter include? (ns.dep/immediate-dependencies depg dep)))
     :node->descriptor (fn [node]
                         (let [{ns-name :name
                                :keys [path project?]} (node->data node)]
                           (prn (= ns-name 'clojure.string))
                           (cond-> {:label ns-name
                                    ;; :group path
                                    }
                             ;; project? (assoc :color :blue)
                             )))
     :edge->descriptor (fn [src dest]
                         (let [{:keys [aliases]} (node->data src)]
                           {:label (some-> aliases (get dest) pr-str)})))))


(defn ->kw [s]
  (keyword (subs s (if (str/starts-with? s ":") 1 0))))

(defn ->sym-list [s]
  (set (map symbol (str/split s #","))))

(def cli-opts
  [[nil "--help" "Print usage info"]
   ["-d" "--deps NAMESPACES"
    (str "Namespaces to be included together with their dependencies.\n"
         "Symbols separated by commas. ex: -d hiccup.core,hiccup.middleware")
    :parse-fn ->sym-list]
   ["-v" "--verbose" "Print debug info."]
   ["-f" "--foreign" "Include namespaces from project dependencies."]
   [nil "--platform PLATFORM" "Which platform (:clj or :cljs)"
    :default :clj
    :parse-fn ->kw
    :validate [#{:clj :cljs} "Either :clj or :cljs"]]
   ["-o" "--output FILE" "Output path"]])

(defn extension [s]
  (second (re-find #"\.([a-zA-Z]+)$" s)))

(defn dot->image
  "Takes a string containing a GraphViz dot file, and renders it to an image.  This requires that GraphViz
   is installed on the local machine."
  [s]
  (let [bytes (:out (sh/sh "dot" "-Tpng" "-y" :in s :out-enc :bytes))]
    (ImageIO/read (io/input-stream bytes))))

(defn save-img [{:keys [output filetype]
                 :or {filetype (extension output)}
                 :as opts}]
  (let [dot (gen-dot opts)]
    (-> dot
        (viz/dot->image)
        (viz/save-image filetype output))))

(defn -main [& args]
  (let [{{:keys [verbose help] :as opts} :options
         :keys [errors summary]}
        (cli/parse-opts args cli-opts)]
    (when verbose (pprint opts))
    (cond (seq errors) (do (run! println errors) (System/exit 1))
          (true? help) (do (println summary) (System/exit 0))
          :else (save-img opts))))

(comment
  (-main "-o" "foo.png" "--foreign")

  (lein.ns-dep/ns-dep-graph {:source-paths ["hiccup/src"]} "-parents" "[hiccup.def]")

  (save-img {:output "foo.png"})

  (-main "-o" "foo.png" #_"-f")

  (-main "-o" "bar.png" "-f")

  )
