(ns repl-tooling.nrepl.nrepl-test
  (:require [devcards.core :as cards]
            [clojure.test :refer [testing is]]
            [check.core :refer [check] :as c]
            [clojure.core.async :as async]
            [check.async :refer [async-test await!]]
            [repl-tooling.nrepl.bencode :as bencode]
            [repl-tooling.integration.fake-editor :as editor]
            [repl-tooling.editor-integration.connection :as conn]))
            ; [repl-tooling.integrations.repls :as repls]))

(cards/deftest bencode
  (testing "encode numbers"
    (check (bencode/encode 210) => "i210e"))

  (testing "encode strings"
    (check (bencode/encode "foo") => "3:foo")
    (check (bencode/encode "foo\n") => "4:foo\n")
    (check (bencode/encode "á") => "2:á"))

  (testing "encode keywords"
    (check (bencode/encode :foo) => "3:foo")
    (check (bencode/encode :foo/bar) => "7:foo/bar"))

  (testing "encode symbols"
    (check (bencode/encode 'foo) => "3:foo")
    (check (bencode/encode 'foo/bar) => "7:foo/bar"))

  (testing "encode lists"
    (check (bencode/encode ["a" "b"]) => "l1:a1:be")
    (check (bencode/encode '("a" "b")) => "l1:a1:be"))

  (testing "encode maps"
    (check (bencode/encode {"a" "b"}) => "d1:a1:be")))

(cards/deftest decode
  (let [decode! (bencode/decoder)]

    (testing "decode numbers"
      (check (decode! "i210e") => [210])
      (check (decode! "i-210e") => [-210])
      (check (decode! "i21ei20e") => [21 20]))

    (testing "decode partially"
      (check (decode! "i21") => [])
      (check (decode! "0ei20e") => [210 20]))

    (testing "decode strings"
      (check (decode! "3:fo") => [])
      (check (decode! "o") => ["foo"])

      (check (decode! "4:foo\n") => ["foo\n"])

      (check (decode! "1:") => [])
      (check (decode! "i") => ["i"]))

    (testing "decode multi-byte strings"
      (check (decode! "2:á3:lá") => ["á" "lá"]))

    (testing "decode lists"
      (check (decode! "li0ei2ee") => [[0 2]]))

    (testing "decode maps"
      (check (decode! "d1:a1:be") => [{"a" "b"}]))

    (testing "decode nested data"
      (check (decode! "d1:a1:bi0eli0ei2eee") => [{"a" "b", 0 [0 2]}]))))

(cards/defcard-rg fake-editor
  editor/editor
  editor/state)

(cards/deftest nrepl-connection
  (let [out (async/chan)]
    (async-test "connecting to a nREPL REPL" {:timeout 8000
                                              :teardown (do
                                                          (swap! editor/state assoc
                                                                 :port 2233)
                                                          (async/close! out)
                                                          (conn/disconnect!))}
      (swap! editor/state assoc :port 3322)
      (editor/connect! {:on-stderr #(swap! editor/state update :stdout
                                           (fn [e] (str e "ERR: " %)))})
      (await! (editor/wait-for #(-> @editor/state :repls :eval)))

      (testing "evaluation works"
        (editor/type-and-eval "(+ 2 3)")
        (check (await! (editor/change-result)) => "5")
        (is (not (re-find #"=>" (editor/txt-for-selector "#stdout")))))

      (testing "exception works"
        (editor/type-and-eval "(/ 10 0)")
        (check (await! (editor/change-result)) => #"java.lang.ArithmeticException"))

      (testing "STDOUT works"
        (editor/type-and-eval "(prn :some-message)")
        (check (await! (editor/change-stdout)) => #":some-message"))

      (testing "STDERR works"
        (editor/type-and-eval "(binding [*out* *err*] (prn :some-error))")
        (check (await! (editor/change-stdout)) => #"ERR: :some-error"))

      (testing "break works"
        (editor/type-and-eval "(Thread/sleep 1000)")
        ((-> @editor/state :commands :break-evaluation :command))
        (check (await! (editor/change-result)) => #"Interrupted")))))