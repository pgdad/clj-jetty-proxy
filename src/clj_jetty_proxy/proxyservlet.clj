(ns clj-jetty-proxy.proxyservlet
  (:require [zookeeper :as zk]
            [clojure.reflect] [clojure.zip :as z]
            [clojure.tools.logging :as log]
            [clj-zoo-service-tracker.core :as tr]
            [clj-jetty-proxy.mapper :as mpr])
  (:import (org.eclipse.jetty.servlets ProxyServlet ProxyServlet$Transparent)
	   (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.http HttpURI)
	   (org.eclipse.jetty.server Server)
	   (org.eclipse.jetty.server.handler HandlerCollection ConnectHandler)
	   (org.eclipse.jetty.server.nio SelectChannelConnector)
           (org.eclipse.jetty.client HttpClient HttpExchange)
           (javax.servlet.http HttpServletRequest HttpServletResponse))
  (:use [clj-jetty-proxy.proxylistener])
  (:gen-class))

(def connections (ref {}))

(def current-connections (agent 0))

(def peak-connections (agent 0))

(add-watch current-connections :peak #(send-off peak-connections max %4))

(def healthcheck-on (ref true))

(defn- trace?
  [traces ^HttpExchange exchange]
  (traces (.toString (.getAddress exchange))))

(defn- add-connection
  [traces connection ^HttpExchange exchange root-node]
  (send-off current-connections inc)
  (if (trace? traces exchange)
    (let [node (str root-node "/" (.getAddress exchange) "/-instance")
        node-reply (zk/create-all connection node :sequential? true :async? true)]
      (dosync
       (alter connections assoc exchange node-reply)))))

(defn- remove-connection
  [trace-it connection exchange]
  (send-off current-connections dec)
  (if trace-it
    (dosync
     (let [node-create-respond-ref (connections exchange)
           node (:name @node-create-respond-ref)]
       (zk/delete connection node :async? true))
     (alter connections dissoc exchange))))

(defn- set-listener
  [traces connection ^HttpExchange exchange]
  (let [old-listener (.getEventListener exchange)]
    (.setEventListener exchange
                       (listener connection
                                 old-listener
                                 #(.isDone exchange)
                                 #(remove-connection (trace? traces exchange)
                                                     connection exchange)))))

(defn- healthcheck
  [req ^HttpServletResponse res]
  (if @healthcheck-on
    (.setStatus res 200)
    (.setStatus res 404))
  )

(defn- set-healthcheck
  [on req ^HttpServletResponse res]
  (.setStatus res 200)
  (dosync (alter healthcheck-on (fn [ & args] on))))

(defn- status
  [req ^HttpServletResponse res]
  (let [wtr (.getWriter res)]
    (.print wtr (str "PEAK=" @peak-connections "\n"))
    (.print wtr (str "CURRENT=" @current-connections "\n"))
    (.print wtr (str "HEALTHCHECK=" @healthcheck-on "\n"))
    (.flush wtr))
  (.setStatus res 200))

(def internal-requests {"/_healthcheck" healthcheck
                        "/_healthcheckOff" (partial set-healthcheck false)
                        "/_healthcheckOn" (partial set-healthcheck true)
                        "/_status" status})

(defn make-proxy
  "creates ProxyServlet that customizes exchange, and configures url"
  ^ProxyServlet [traces-ref connection connections-root-node url-mapper]
  (proxy [org.eclipse.jetty.servlets.ProxyServlet] []
    (customizeExchange [exchange request]
      (let [traces @traces-ref]
        (add-connection traces connection exchange connections-root-node)
        (set-listener traces connection exchange)))
    (proxyHttpURI [request uri]
      (let [url (url-mapper request uri)]
        (if url
          (HttpURI. ^String url)
          nil)))
    (service [req res]
      (if-let [service-f (internal-requests (.getRequestURI ^HttpServletRequest req))]
        (service-f req res)
        (proxy-super service req res)))))

(defn -main
  [keepers region]
  (let [server (Server.)
        connector (SelectChannelConnector.)
        handlers (HandlerCollection.)
        connections-root-node "/connections"
        ]
    (let [tracker (tr/initialize keepers region)]
      (.setPort connector 8888)
      (.setHost connector  (.. java.net.InetAddress getLocalHost getHostName))
      (.addConnector server connector)
      (.setHandler server handlers)
      ;; setup proxy servlet
      (let [context (ServletContextHandler.
                     handlers "/" ServletContextHandler/SESSIONS)
            proxyServlet (ServletHolder.
                          (make-proxy (:traces-ref @tracker)
                                      (:client @(:instances @tracker))
                                      connections-root-node
                                      (partial mpr/req->url tracker)))
            proxy (ConnectHandler.)]
        (.addServlet context proxyServlet "/*")
        (.addHandler handlers proxy)
        (.start server)))))

(defn main2
  [keepers region]
  (let [tracker (tr/initialize keepers region)]
    tracker))
