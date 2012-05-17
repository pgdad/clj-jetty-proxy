(ns clj-jetty-proxy.proxyservlet
  (:require [zookeeper :as zk]
            [clojure.reflect] [clojure.zip :as z]
            [clojure.tools.logging :as log]
            [clj-zoo-service-tracker.core :as tr])
  (:import (org.eclipse.jetty.servlets ProxyServlet ProxyServlet$Transparent)
	   (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.http HttpURI)
	   (org.eclipse.jetty.server Server)
	   (org.eclipse.jetty.server.handler HandlerCollection ConnectHandler)
	   (org.eclipse.jetty.server.nio SelectChannelConnector)
           (org.eclipse.jetty.client HttpClient))
  (:use [clj-jetty-proxy.proxylistener])
  (:gen-class))

(def connections (ref {}))

(def current-connections (agent 0))

(def peak-connections (agent 0))

(add-watch current-connections :peak #(send-off peak-connections max %4))

(def healthcheck-on (ref true))

(defn- trace?
  [traces exchange]
  (traces (.toString (.getAddress exchange))))

(defn- add-connection
  [traces connection exchange root-node]
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
  [traces connection exchange]
  (let [old-listener (.getEventListener exchange)]
    (.setEventListener exchange
                       (listener connection
                                 old-listener
                                 #(.isDone exchange)
                                 #(remove-connection (trace? traces exchange)
                                                     connection exchange)))))

(defn- healthcheck
  [req res]
  (if @healthcheck-on
    (.setStatus res 200)
    (.setStatus res 404))
  )

(defn- set-healthcheck
  [on req res]
  (.setStatus res 200)
  (dosync (alter healthcheck-on (fn [ & args] on))))

(defn- status
  [req res]
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
  [traces-ref connection connections-root-node url-mapper]
  (proxy [org.eclipse.jetty.servlets.ProxyServlet] []
    (customizeExchange [exchange request]
      (let [traces @traces-ref]
        (add-connection traces connection exchange connections-root-node)
        (set-listener traces connection exchange)))
    (proxyHttpURI [request uri]
      (let [url (url-mapper request uri)]
        (if url
          (HttpURI. url)
          nil)))
    (service [req res]
      (if-let [service-f (internal-requests (.getRequestURI req))]
        (service-f req res)
        (proxy-super service req res)))))

(defn- verify-client
  [client-regs-ref client-id service]
  (dosync
   (let [registrations (ensure client-regs-ref)
         servs-for-client (registrations client-id)]
     (log/spy :debug (str "CLIENT REGISTRATIONS: " registrations))
     (and servs-for-client (contains? servs-for-client service)))))

(defn- mapper-fn
  "returns a string to proxy to, available parameters are obtained from the incoming request"
  [tracker-ref request uri]
  (do
    (let [
          ]
      )
    (let [my-region (:my-region @tracker-ref)
          routes-multi (:routes-multi @tracker-ref)
          service (.getHeader request "x-service-name")
	  client-id (.getHeader request "x-client-id")
	  client-verified (verify-client (:client-regs-ref @tracker-ref) client-id service)
	  major (.getIntHeader request "x-service-version-major")
	  minor (.getIntHeader request "x-service-version-minor")]
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
        ;; check to see if version is asked for
        (tr/lookup-service tracker-ref service major minor uri client-id)))))

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
                                      (partial mapper-fn tracker)))
            proxy (ConnectHandler.)]
        (.addServlet context proxyServlet "/*")
        (.addHandler handlers proxy)
        (.start server)))))

(defn main2
  [keepers region]
  (let [tracker (tr/initialize keepers region)]
    tracker))
