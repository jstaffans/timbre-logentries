(ns timbre-logentries.core
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre]))

;; Based on the Logentries java.util.logging appender:
;; https://github.com/logentries/le_java/blob/master/src/main/java/com/logentries/jul/LogentriesHandler.java

(def ^:dynamic *le-conn* nil)

(def le-conn-retries (atom 0))

(def space (byte \ ))

(def newline (byte-array [(byte 0x0D) (byte 0x0A)]))

(defn- connect
  "Connect to Logentries."
  [hostname port]
  (if (< @le-conn-retries 5)
    (try
      (alter-var-root
       #'*le-conn*
       (fn [_]
         (doto (.socket (java.nio.channels.SocketChannel/open))
           (.connect (java.net.InetSocketAddress. hostname port) 2000))))
      (reset! le-conn-retries 0)
      (catch java.io.IOException e
        (swap! le-conn-retries inc)
        (timbre/error "Could not connect to Logentries:" e)))))

(defn- disconnect
  []
  (when *le-conn* (.close *le-conn*))
  (alter-var-root #'*le-conn* (fn [_] nil)))

(defn- connected?
  []
  (let [conn *le-conn*]
    (and conn (.isConnected conn))))

(defn- write-to-buffer
  [^java.nio.ByteBuffer buffer token msg]
  (try
    (doto buffer
      .clear
      (.put (.getBytes token))
      (.put space)
      (.put (.getBytes msg (java.nio.charset.Charset/forName "UTF-8")))
      (.put newline)
      .flip)
    (catch java.nio.BufferOverflowException e
      (timbre/error "Could not send log to Logentries: buffer overflow")
      (doto buffer .clear))))

(defn- publish
  [buffer]
  (try
    (while (.hasRemaining buffer)
      (.write (.getChannel *le-conn*) buffer))
    (catch Exception e
      (disconnect)
      (timbre/error "Could not publish logs to Logentries:" e))))

(def raw-output
  "Sends raw output to Logentries. Useful for e.g. JSON logging."
  (fn [data]
    (let [args @(:vargs_ data)]
      (first args))))

(def default-output-no-timestamp
  "The default output function, minus timestamp."
  (fn [data]
    (timbre/default-output-fn (assoc data :timestamp_ (delay "")))))

(defn split-lines-with-indent
  [s]
  (let [lines (str/split-lines s)
        indents (into [""] (repeat (- (count lines) 1) "... "))]
    (map str indents lines)))

(defn logentries-appender
  [{:keys [token hostname port output-fn] :or {hostname "data.logentries.com" port 514 output-fn default-output-no-timestamp}}]
  {:pre [(not (nil? token))]}
  (let [buffer (java.nio.ByteBuffer/allocate 8192)
        _      (connect hostname port)]
    {:enabled?   true
     :async      true
     :rate-limit nil
     :output-fn  output-fn
     :fn         (fn [data]
                   (when (not (connected?))
                     (connect hostname port))

                   (when (connected?)
                     (let [{:keys [output-fn]} data
                           output-str          (output-fn data)]
                       (doseq [line (split-lines-with-indent output-str)]
                         (write-to-buffer buffer token line)
                         (publish buffer)))))}))


(comment
  (taoensso.timbre/merge-config!
   {:level :debug
    :appenders {:logentries (logentries-appender {:token "***"
                                                  :output-fn raw-output})}})
  (taoensso.timbre/info "{\"foo\": \"bar\"}")
  (taoensso.timbre/info "foo\nbar"))
