(ns clj-jetty-proxy.proxyservlet
  (:require [zookeeper :as zk] [clj-zoo-watcher.core :as w]
            [clojure.reflect] [clj-tree-zipper.core :as tz] [clojure.zip :as z]
            [clojure.tools.logging :as log] [clj-zoo-service-tracker.core :as tr])
  (:import (org.eclipse.jetty.servlets ProxyServlet ProxyServlet$Transparent)
	   (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.http HttpURI)
	   (org.eclipse.jetty.server Server)
	   (org.eclipse.jetty.server.handler HandlerCollection ConnectHandler)
	   (org.eclipse.jetty.server.nio SelectChannelConnector))
  (:gen-class))

(defn make-proxy
  "takes a 2 ary function that returns a url string"
  [url-mapper]
  (proxy [org.eclipse.jetty.servlets.ProxyServlet] []
    (proxyHttpURI [request uri]
      (let [url (url-mapper request uri)]
        (if url
          (HttpURI. url)
          nil)))))

(defn- verify-client
  [client-regs-ref client-id service]
  (dosync
   (let [registrations (ensure client-regs-ref)
         servs-for-client (registrations client-id)]
     (and servs-for-client (contains? servs-for-client service)))))

(defn- mapper-fn
  "returns a string to proxy to, available parameters are obtained from the incoming request"
  [tracker-ref request uri]
  (do
    (w/print-tree (:zipper @(:routes @tracker-ref)))
    (let [service (.getHeader request "service-name")
	  client-id (.getHeader request "client-id")
	  client-verified (verify-client (:client-regs-ref @tracker-ref) client-id service)
	  major (.getIntHeader request "service-version-major")
	  minor (.getIntHeader request "service-version-minor")]
      (log/spy :debug (str "CLIENT ID: " client-id))
      (log/spy :debug (str "CLIENT VERIFIED: " client-verified))
      (log/spy :debug (str "SERVICE NAME: " service))
      (log/spy :debug (str "SERVICE MAJOR: " major))
      (log/spy :debug (str "SERVICE MINOR: " minor))
      (log/spy :debug (str "URI: " uri))
      (if-not (and service client-verified)
        ;; nil here means the 'service-name' is not in request
        ;; or client is not allowed to access service
        nil
        (let [serv-tree (tz/find-path (:zipper @(:routes @tracker-ref))
                                      (list "/" service))]
          (if-not serv-tree
            ;; this means that service is not registered
            nil
            ;; service at least has existed
            ;; we still might not have any currently
            ;; available

            ;; check to see if version is asked for
            (let [selected-service (tr/lookup-service tracker-ref service major minor uri)]
              (log/spy :debug (str "SELECTED: " selected-service))
              selected-service)))))))

(defn -main
  [keepers env app region]
  (let [server (Server.)
        connector (SelectChannelConnector.)
        handlers (HandlerCollection.)
        ]
    (let [tracker (tr/initialize keepers env app region)]
      (. connector setPort 8888)
      (. server addConnector connector)
      (. server setHandler handlers)
      ;; setup proxy servlet
      (let [context (ServletContextHandler.
                     handlers "/" ServletContextHandler/SESSIONS)
            proxyServlet (ServletHolder.
                          (make-proxy (partial mapper-fn tracker)))
            proxy (ConnectHandler.)]
        (. context addServlet proxyServlet "/*")
        (. handlers addHandler proxy)
        (. server start)))))

(defn main2
  [keepers env app region]
  (let [tracker (tr/initialize keepers env app region)]
    tracker))
