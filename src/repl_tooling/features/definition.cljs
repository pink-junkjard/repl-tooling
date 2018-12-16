(ns repl-tooling.features.definition
  (:require [repl-tooling.eval :as eval]
            [repl-tooling.editor-helpers :as editor-helpers]
            [cljs.core.async :as async :include-macros true]))

(defn- cmd-for-filename [the-var]
  `(clojure.core/let [res# (clojure.core/meta (clojure.core/resolve (quote ~the-var)))]
    (clojure.core/require 'clojure.java.io)
    (clojure.core/symbol
     (clojure.core/pr-str
      [(clojure.core/some->> (:file res#)
                             (.getResource (clojure.lang.RT/baseLoader))
                             .getPath)
       (:line res#)]))))

(defn- cmd-for-read-jar [jar-file-name]
  `(clojure.core/let [[jar# path#] (clojure.string/split ~jar-file-name #"!/" 2)
                      jar# (clojure.string/replace-first jar# #"file:" "")
                      jar-file# (java.util.jar.JarFile. jar#)
                      ba# (java.io.ByteArrayOutputStream.)
                      is# (.getInputStream jar-file# (.getJarEntry jar-file# path#))]
     (clojure.java.io/copy is# ba#)
     (clojure.core/symbol
      (java.lang.String. (.toByteArray ba#)))))

(defn- get-result [repl [file-name line] resolve]
  (if (nil? file-name)
    (resolve nil)
    (let [chan (async/chan)]
      (async/go
       (if (re-find #"\.jar!/" file-name)
         (let [cmd (cmd-for-read-jar file-name)]
           (eval/evaluate repl cmd {:ignore true} #(async/put! chan (:result %)))
           (resolve {:file-name file-name
                     :line (dec line)
                     :contents (async/<! chan)}))
         (resolve {:file-name file-name :line (dec line)}))
       (async/close! chan)))))

(defn find-var-definition [repl ns-name symbol-name]
  (js/Promise.
   (fn [resolve]
     (let [chan (async/chan)]
       (async/go
        (prn :WAT (str "`" symbol-name))
        (eval/evaluate repl (str "`" symbol-name) {:namespace ns-name :ignore true}
                       #(async/put! chan %))
        (if-let [fqn (some-> (async/<! chan) :result symbol)]
          (let [_ (prn :DONEEEEE)
                cmd (cmd-for-filename fqn)]
            (prn [:CMD cmd])
            (eval/evaluate repl cmd {:ignore true}
                           #(async/put! chan (:result (editor-helpers/parse-result %))))
            (get-result repl (async/<! chan) resolve))
          (prn [:ERROR!]))
        (async/close! chan))))))
