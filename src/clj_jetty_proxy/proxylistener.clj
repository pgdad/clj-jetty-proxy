(ns clj-jetty-proxy.proxylistener
  (:require [zookeeper :as zk] [clj-jetty-proxy.stats :as stats])
  (:import (org.eclipse.jetty.client HttpEventListener HttpExchange))
  (:gen-class))

(defn- do-stats-error
  [error-stat]
  (stats/increment error-stat))

(defn- do-stats
  [remover k error?]
  (let [attr (remover k)]
    (if-not attr
      (println "BAD STATS")
      (let [mtrc (stats/metric-from-map attr)
            t (- (System/currentTimeMillis) (:start attr))]
        (stats/increment attr)
        (stats/timing attr t)
        (if error?
          (do-stats-error (assoc attr :service (str "error." (:service attr))))))
      )))

(defn listener
  [^HttpEventListener old-listener exchange remover done-f]

  (proxy [org.eclipse.jetty.client.HttpEventListener] []
    (onRequestCommitted []
      (.onRequestCommitted old-listener))
    (onRequestComplete []
      (.onRequestComplete old-listener)
      (when (done-f)
        (try
          (do-stats remover exchange false)
          (catch Exception ex (println (str "DONE EX:" ex))))
        ))
    (onResponseStatus [version status reason]
      (.onResponseStatus old-listener version status reason))
    (onResponseHeader [name value]
      (.onResponseHeader old-listener name value))
    (onResponseHeaderComplete []
      (.onResponseHeaderComplete old-listener))
    (onResponseContent [content]
      (.onResponseContent old-listener content))
    (onResponseComplete []
      (.onResponseComplete old-listener)
      (when (done-f)
        (try
          (do-stats remover exchange false)
          (catch Exception ex (println (str "DONE EX:" ex))))
        ))
    (onConnectionFailed [ex]
      (do-stats remover exchange true)
      (.onConnectionFailed old-listener ex))
    (onException [ex]
      (do-stats remover exchange true)
      (.onException old-listener ex))
    (onExpire []
      (do-stats remover exchange true)
      (.onExpire old-listener))
    (onRetry []
      (.onRetry old-listener))
      ))
