(ns clj-jetty-proxy.proxyservlet
  (:import (org.eclipse.jetty.servlets ProxyServlet ProxyServlet$Transparent)
           (org.eclipse.jetty.http HttpURI))
  (:gen-class))

(defn make-proxy
  "takes a 4 ary function that returns a url string"
  [url-mapper]
  (proxy [org.eclipse.jetty.servlets.ProxyServlet] []
    (proxyHttpURI [scheme server port uri]
      (HttpURI. (url-mapper scheme server port uri)))))
