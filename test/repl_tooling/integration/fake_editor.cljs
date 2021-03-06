(ns repl-tooling.integration.fake-editor
  (:refer-clojure :exclude [type])
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [reagent.core :as r]
            [clojure.core.async :as async :include-macros true]
            [repl-tooling.editor-integration.renderer :as render]
            [repl-tooling.editor-integration.connection :as conn]
            [repl-tooling.commands-to-repl.all-cmds :as cmds]))

(defn wait-for [f]
  (async/go
   (loop [t 0]
     (when (< t 100)
       (if-let [res (f)]
         res
         (do
           (async/<! (async/timeout 100))
           (recur (inc t))))))))

(defonce state (r/atom {:host "localhost"
                        :port 2233
                        :code "(do (defrecord Foo [a b]) (->Foo (range 20) 20))"
                        :repls {:eval nil
                                :aux nil}
                        :commands {}
                        :stdout nil
                        :stderr nil
                        :range [[0 0] [0 0]]
                        :eval-result (r/atom nil)}))

(defn- res [result]
  (p/let [parse (-> @state :features :result-for-renderer)
          res (parse result)]
    (reset! (:eval-result @state) res)
    (swap! state update :stdout (fn [e] (str e "=> " (-> result :result :as-text) "\n")))))

(defn evaluate []
  (let [lines (-> @state :code str/split-lines)
        eval-sel (-> @state :commands :evaluate-selection :command)]
    (swap! state assoc :range [[0 0] [(-> lines count dec) (-> lines last count dec)]])
    (eval-sel)))

(defn run-command! [command]
  (if-let [cmd (get-in @state [:commands command :command])]
    (cmd)
    (prn "Command not found" command)))

(defn type [txt] (swap! state assoc :code txt))
(defn type-and-eval [txt]
  (type txt)
  (evaluate))

(defn change-stdout []
  (let [old (:stdout @state)]
    (wait-for #(and (not= old (:stdout @state))
                    (:stdout @state)))))

(defn txt-for-selector [sel]
  (str (some-> js/document
               (.querySelector sel)
               .-innerText
               .trim)))

(defn change-result []
  (let [old (txt-for-selector "#result")]
    (wait-for #(and (not= old (txt-for-selector "#result"))
                    (txt-for-selector "#result")))))

(defn handle-disconnect []
  (reset! (:eval-result @state) nil)
  (swap! state assoc
         :repls {:eval nil :aux nil}
         :stdout nil :stderr nil
         :commands {}))

(defn connect!
  ([] (connect! {}))
  ([additional-callbacks]
   (if (-> @state :repls :eval)
     (.resolve js/Promise @state)
     (.
       (conn/connect! (:host @state) (:port @state)
                      (merge {:on-disconnect handle-disconnect
                              :on-stdout #(swap! state update :stdout (fn [e] (str e %)))
                              :on-eval res
                              :notify identity
                              :on-stderr #(swap! state update :stderr (fn [e] (str e %)))
                              :editor-data #(let [code (:code @state)]
                                              {:contents code
                                               :filename "foo.clj"
                                               :range (:range @state)})}
                             additional-callbacks))
       (then (fn [res]
               (swap! state assoc :repls {:eval (:clj/repl @res)
                                          :aux (:clj/aux @res)}
                      :commands (:editor/commands @res)
                      :features (:editor/features @res)
                      :stdout "" :stderr "")))))))

(defn disconnect! [] (cmds/disconnect!))

(defn editor [state]
  [:div
   [:h4 "Socket REPL connections"]
   [:p [:b "Hostname: "] [:input {:type "text" :value (:host @state)
                                  :on-change #(->> % .-target .-value (swap! state assoc :host))}]
       [:b " Port: "] [:input {:type "text" :value (:port @state)
                               :on-change #(->> % .-target .-value int (swap! state assoc :port))}]]
   [:textarea {:style {:width "100%" :height "100px"}
               :value (:code @state)
               :on-change #(->> % .-target .-value (swap! state assoc :code))}]
   [:p
    (if (-> @state :repls :eval)
      [:span
       [:button {:on-click evaluate}
        "Evaluate"] " "
       [:button {:on-click disconnect!} "Disconnect!"]]
      [:button {:on-click #(connect!)} "Connect!"])]
   [:p (if (-> @state :repls :eval) "Connected" "Disconnected")]
   [:div
    (when-let [res @(:eval-result @state)]
      [:div
       [:h5 "RESULT"]
       [:pre
        [:div {:id "result" :class "result"}
         (render/view-for-result res)]]])]
   (when-let [out (:stdout @state)]
     [:div
      [:h5 "STDOUT"]
      [:pre out]])
   (when-let [out (:stderr @state)]
     [:div
      [:h5 "STDERR"]
      [:pre out]])])
