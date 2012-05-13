(ns clj-jetty-proxy.proxylistener
  (:require [zookeeper :as zk])
  (:import (org.eclipse.jetty.client.HttpEventListener))
  (:gen-class))

(defn listener
  [connection old-listener done-f remove-f]

  (proxy [org.eclipse.jetty.client.HttpEventListener] []
    (onRequestCommitted []
      (.onRequestCommitted old-listener))
    (onRequestComplete []
      (.onRequestComplete old-listener)
      (if (done-f)
        (remove-f)))
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
      (if (done-f)
        (remove-f)))
    (onConnectionFailed [ex]
      (remove-f)
      (.onConnectionFailed old-listener ex))
    (onException [ex]
      (remove-f)
      (.onException old-listener ex))
    (onExpire []
      (remove-f)
      (.onExpire old-listener))
    (onRetry []
      (.onRetry old-listener))
      ))
