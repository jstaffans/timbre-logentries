(ns timbre-logentries.core
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre]))

;; Based on the Logentries java.util.logging appender:
;; https://github.com/logentries/le_java/blob/master/src/main/java/com/logentries/jul/LogentriesHandler.java

(def ^:private le-conn (atom nil))

(def ^:private space (byte \ ))

(def ^:private newline (byte-array [(byte 0x0D) (byte 0x0A)]))

(defn- connect
  "Connect to Logentries."
  [hostname port]
  (try
    (doto (.socket (java.nio.channels.SocketChannel/open))
      (.connect (java.net.InetSocketAddress. hostname port) 2000))
    (catch java.io.IOException e
      (timbre/error "Could not connect to Logentries:" e))))

(defn- disconnect
  []
  (when-let [conn @le-conn]
    (.close conn))
  (reset! le-conn nil))

(defn- ensure-connected
  [hostname port]
  (swap! le-conn #(or % (connect hostname port))))

(defn- connected?
  []
  (let [conn @le-conn]
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
      (.write (.getChannel @le-conn) buffer))
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

(defn- split-lines-with-indent
  [s]
  (let [lines (str/split-lines s)
        indents (into [""] (repeat (- (count lines) 1) "... "))]
    (map str indents lines)))

(defn logentries-appender
  [{:keys [token hostname port output-fn buffer-size] :or {hostname "data.logentries.com" port 514 output-fn default-output-no-timestamp buffer-size 8192}}]
  {:pre [(not (nil? token))]}
  (let [buffer (java.nio.ByteBuffer/allocate buffer-size)]
    {:enabled?   true
     :async      true
     :rate-limit nil
     :output-fn  output-fn
     :fn         (fn [data]
                   (ensure-connected hostname port)
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
