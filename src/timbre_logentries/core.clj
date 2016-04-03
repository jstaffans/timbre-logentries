(ns timbre-logentries.core
  (:require [taoensso.timbre :as timbre]))

;; Based on the Logentries java.util.logging appender:
;; https://github.com/logentries/le_java/blob/master/src/main/java/com/logentries/jul/LogentriesHandler.java

(def space (byte \ ))

(def newline (byte-array [(byte 0x0D) (byte 0x0A)]))

(defn- connect
  "Connect to Logentries with a 2 second timeout."
  [hostname port]
  (try
    (doto (.socket (java.nio.channels.SocketChannel/open))
      (.connect (java.net.InetSocketAddress. hostname port) 2000))
    (catch java.io.IOException e
      (timbre/error "Could not connect to Logentries:" e))))

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
  [buffer channel]
  (try
    (while (.hasRemaining buffer)
      (.write channel buffer))
    (catch Exception e
      (timbre/error "Could not publish logs to Logentries:" e))))

(defn logentries-appender
  [{:keys [token hostname port] :or {hostname "data.logentries.com" port 514}}]
  {:pre [(not (nil? token))]}
  (let [buffer  (java.nio.ByteBuffer/allocate 4096)
        channel (.getChannel (connect hostname port))]
    {:enabled?   true
     :async      true
     :rate-limit nil

     ;; no timestamp, logentries prepends one
     :output-fn  (fn [data] (timbre/default-output-fn (assoc data :timestamp_ (delay ""))))

     :fn         (fn [data]
                    (let [{:keys [output-fn]} data
                          output-str          (output-fn data)]
                      (write-to-buffer buffer token output-str)
                      (publish buffer channel)))}))


(comment
  (taoensso.timbre/merge-config!
   {:level :debug
    :appenders {:logentries (logentries-appender {:token "***"})}})

  (taoensso.timbre/info "Sed diam. In id erat non orci commodo lobortis.")
  )
