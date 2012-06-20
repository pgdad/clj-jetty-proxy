(ns clj-jetty-proxy.HSRequestWrapper
  (:import (java.io ByteArrayInputStream)
           (javax.servlet ServletInputStream)
           (javax.servlet.http HttpServletRequest HttpServletRequestWrapper))
  (:gen-class
   :extends javax.servlet.http.HttpServletRequestWrapper
   :constructors {[javax.servlet.http.HttpServletRequest]
                  [javax.servlet.http.HttpServletRequest]}
   :state state
   :post-init post-init
   :init init))

(defn -init
  [^javax.servlet.http.HttpServletRequest request]
  [[request] (ref {})])

(defn -post-init
  [this request]
  (let [s (.state this)]
    (dosync
     (alter s assoc-in [:request] request))
    ))

(defn- slurp-input
  "copy real input stream contents into a string, store it in session"
  [this request]
  (let [s (.state this)]
    (if-let [instream (.getInputStream (:request @s))]
      (dosync
       (alter s assoc-in [:content] (slurp instream)))
      (dosync
       (alter s assoc-in [:content] "")))))

(defn -getInputStream
  [this]
  (let [s (.state this)
        request (:request @s)]
    ;; if POST request save input content, so that it can be forwarded later
    (if (= "POST" (.getMethod request))
      (do
        (if-not (:content @s)
          (slurp-input this request))
        (let [barray-input-stream (java.io.ByteArrayInputStream.
                                   (.getBytes (:content @s)))]
          (proxy [javax.servlet.ServletInputStream] []
            (read
              ([] (.read barray-input-stream))
              ([a b c] (.read barray-input-stream a b c))))))
      (.getInputStream (:request @s))))
)
