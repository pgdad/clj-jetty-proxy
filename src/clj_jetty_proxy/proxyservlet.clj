(ns clj-jetty-proxy.proxyservlet
  (:require [zookeeper :as zk]
            [clojure.reflect] [clojure.zip :as z]
            [clojure.tools.logging :as log]
            [clj-zoo-service-tracker.core :as tr]
            [clj-jetty-proxy.mapper :as mpr]
            [clj-jetty-proxy.HSRequestWrapper :as rwrapper]
            [clj-jetty-proxy.stats :as stats])
  (:import (org.eclipse.jetty.servlets ProxyServlet ProxyServlet$Transparent)
	   (org.eclipse.jetty.servlet FilterMapping ServletContextHandler ServletHolder)
           (org.eclipse.jetty.http HttpURI)
           (org.eclipse.jetty.util.ssl SslContextFactory)
	   (org.eclipse.jetty.server Server)
	   (org.eclipse.jetty.server.handler HandlerCollection ConnectHandler)
	   (org.eclipse.jetty.server.nio SelectChannelConnector)
	   (org.eclipse.jetty.server.ssl SslSelectChannelConnector)
           (org.eclipse.jetty.client HttpClient HttpExchange)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (javax.servlet DispatcherType)
           (java.lang.management ManagementFactory)
           (java.util EnumSet))
  (:use [clj-jetty-proxy.proxylistener])
  (:gen-class))

(def connections (ref {}))

(def healthcheck-on (ref true))

(defn- trace?
  [traces ^HttpExchange exchange]
  (traces (str (.getAddress exchange))))

;; maps exchange -> service def
(def exchange-map (ref {}))

;; maps request -> service def
(def request-to-service-map (ref {}))

(defn- request-to-service-adder
  [request service]
  (dosync
   (alter request-to-service-map assoc-in [request] service)))

(defn- request-to-service-rm
  [request]
  (dosync
   (alter request-to-service-map dissoc request)))

(defn- request-to-service-val
  [request]
  (@request-to-service-map request))

(defn- request-to-service-remover
  [request]
  (let [v (request-to-service-val request)]
    (if v (request-to-service-rm request))
    v))

;; add service def to exhange -> service def map
;; first obtain it from the request -> map
(defn- exchange-map-adder
  [exchange request]
  (let [service (request-to-service-remover request)]
    (dosync
     (alter exchange-map assoc-in [exchange] service))))

(defn- exchange-map-rm
  [exchange]
  (dosync
   (alter exchange-map dissoc exchange)))

(defn- exchange-map-val
  [exchange]
  (@exchange-map exchange))

(defn- exchange-map-remover
  [exchange]
  (let [v (exchange-map-val exchange)]
    (if v (exchange-map-rm exchange))
    v))

(defn- set-listener
  [^HttpExchange exchange request]
  (exchange-map-adder exchange request)
  (let [old-listener (.getEventListener exchange)]
    (.setEventListener exchange
                       (listener old-listener
                                 exchange
                                 exchange-map-remover
                                 #(.isDone exchange)))))

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
    (.print wtr (str "HEALTHCHECK=" @healthcheck-on "\n"))
    (.flush wtr))
  (.setStatus res 200))

(defn- metrics
  [req ^HttpServletResponse res]
  (let [wtr (.getWriter res)
        runtime (ManagementFactory/getRuntimeMXBean)
        uptime (.getUptime runtime)
	threads (ManagementFactory/getThreadMXBean)
	threadCount (.getThreadCount threads)
	startedThreadsCount (.getTotalStartedThreadCount threads)
	daemonCount (.getDaemonThreadCount threads)
	peakCount (.getPeakThreadCount threads)
	threadIds (.getAllThreadIds threads)
        totalCpuTime (reduce #(+ %1 (.getThreadCpuTime threads %2)) threadIds)
        totalUserTime (reduce #(+ %1 (.getThreadUserTime threads %2)) threadIds)
	]
    (.println wtr (str "THREAD-COUNT=" threadCount))
    (.println wtr (str "STARTED-THREAD-COUNT=" startedThreadsCount))
    (.println wtr (str "DAEMON-COUNT=" daemonCount))
    (.println wtr (str "PEAK-COUNT=" peakCount))
    (.println wtr (str "THREADIDS=" threadIds))
    (.println wtr (str "UPTIME=" uptime))
    (.println wtr (str "TOTAL-CPU-TIME=" totalCpuTime))
    (.println wtr (str "TOTAL-USER-TIME=" totalUserTime))
    ))

  (def internal-requests {"/_healthcheck" healthcheck
                        "/_healthcheckOff" (partial set-healthcheck false)
                        "/_healthcheckOn" (partial set-healthcheck true)
                        "/_status" status
                        "/_metrics" metrics})

(defn- make-proxy
  "creates ProxyServlet that customizes exchange, and configures url"
  ^ProxyServlet [url-mapper]

  (proxy [org.eclipse.jetty.servlets.ProxyServlet] []
    (customizeExchange [exchange request]
      (set-listener exchange request))
    (proxyHttpURI [request uri]
      (let [url (url-mapper request-to-service-adder request uri)]
        (when url (HttpURI. ^String url))))
    (service [req res]
      (if-let [service-f (internal-requests (.getRequestURI ^HttpServletRequest req))]
        (service-f req res)
        (proxy-super service (clj_jetty_proxy.HSRequestWrapper. req) res)))))

(defn- make-connector
  [keystore keystore-passwd]
  (if keystore
    (let [fact (SslContextFactory. keystore)
          _ (.setKeyStorePassword fact keystore-passwd)]
      (SslSelectChannelConnector. fact))
      (SelectChannelConnector.)))

(defn main-with-body-examiner
  [body-ex-fun keepers region port
   & {:keys [ks kspasswd] :or {ks nil kspasswd nil}}]
  (let [server (Server.)
        connector (make-connector ks kspasswd)
        handlers (HandlerCollection.)
        ]
    (stats/setup)
    (let [tracker (tr/initialize keepers region)]
      (.setPort connector (read-string port))
      #_(.setHost connector  (.. java.net.InetAddress getLocalHost getHostName))
      (.addConnector server connector)
      (.setHandler server handlers)
      ;; setup proxy servlet
      (let [context (ServletContextHandler.
                     handlers "/" ServletContextHandler/SESSIONS)
            proxyServlet (ServletHolder.
                          (make-proxy
                           (partial mpr/req->url body-ex-fun tracker)))
            proxy (ConnectHandler.)]
        (.addServlet context proxyServlet "/*")
        (.addHandler handlers proxy)
        (.start server)))))

(defn -main
  [keepers region port]
  (main-with-body-examiner nil keepers region port))
