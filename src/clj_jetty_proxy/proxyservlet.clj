(ns clj-jetty-proxy.proxyservlet
  (:require [zookeeper :as zk] [clj-zoo-watcher.core :as w]
            [clojure.reflect] [clj-tree-zipper.core :as tz]
            [clojure.zip :as z])
  (:import (org.eclipse.jetty.servlets ProxyServlet ProxyServlet$Transparent)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.http HttpURI)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.handler HandlerCollection ConnectHandler)
           (org.eclipse.jetty.server.nio SelectChannelConnector))
  (:gen-class))

(def ^:dynamic *file-to-data* (ref {}))

(def ^:dynamic *zk-root-node* "/services/PROD/SI/DC1")

(def ^:dynamic *route-watcher* (ref nil))

(def uri-split-pattern (re-pattern "/"))

(def nl-split-pattern (re-pattern "\n"))

(def version-split-pattern (re-pattern "\\."))

(defn- get-file-data
  [client file-node]
  (String. (:data (zk/data client file-node)) "UTF-8"))

(defn- file-created
  [client file-node]
  (dosync
   (let [data (get-file-data client file-node)
         serv-def (clojure.string/replace-first file-node
                                                (str *zk-root-node* "/") "")
         serv-parts (clojure.string/split serv-def uri-split-pattern)
         service (first serv-parts)
         major (read-string (second serv-parts))
         minor (read-string (nth serv-parts 2))
         data-parts (clojure.string/split data nl-split-pattern)
         value {:service service :major major
                :data-version (first data-parts)
                :instance-node (second data-parts)
                :url (nth data-parts 2)}
         f-to-data (ensure *file-to-data*)]
     (println (str "---VALUE: " value))
     (alter *file-to-data* (fn [& args] (assoc f-to-data file-node value))))))

(defn- file-removed
  [client file-node]
  (dosync
   (let [f-to-data (ensure *file-to-data*)]
     (alter *file-to-data*
            (fn [& args] (dissoc f-to-data file-node))))))

(defn make-proxy
  "takes a 4 ary function that returns a url string"
  [url-mapper]
  (proxy [org.eclipse.jetty.servlets.ProxyServlet] []
    (proxyHttpURI [scheme server port uri]
      (let [url (url-mapper scheme server port uri)]
        (if url
          (HttpURI. url)
          nil)))))

(defn- uri-parts
  [uri]
  (clojure.string/split uri uri-split-pattern))

(defn- extract-service-parts
  [parts]
  (let [cnt (count parts)
        tmp (rest parts)
        service (first tmp)
        tmp2 (rest tmp)
        version (first tmp2)
        split-version (clojure.string/split version version-split-pattern)
        ver-major (read-string (first split-version))
        ver-minor (if (= 2 (count split-version))
                    (read-string second split-version)
                    0)
        r-path (clojure.string/join "/" (rest tmp2))
        m {:service service
           :ver-major ver-major
           :ver-minor ver-minor
           :uri r-path}]
    m))

(defn- get-service-from-parts
  [parts]
  [(second parts) (rest (rest parts))])

(defn- get-version-from-parts
  [parts]
  (if (= parts '())
    nil
    (let [version (read-string (first parts))
          v-type (clojure.reflect/reflect version)
          bases (:bases v-type)
          is-version (contains? bases 'java.lang.Number)
          is-minor (and is-version (< 0 (.indexOf (first parts) ".")))
          minor-part (if is-minor
                       (second (clojure.string/split (first parts) version-split-pattern))
                       nil)
          minor (if minor-part
                  (read-string minor-part)
                  0)]
      (if is-minor
        (list (read-string (first (clojure.string/split
                                   (first parts) version-split-pattern))) minor)
        (if is-version
          (list version)
          '())))))

(defn- major-minor-order
  [item]
  (let [serv-data (@*file-to-data* item)
        res (+ 10000 (:major serv-data) (:minor serv-data))]
    res))

(defn- major-minor-order-rev
  [item]
  * -1 (major-minor-order item))

(defn- lookup-latest
  "returns nil if no services available, else returns the highest versioned one"
  [service]
  (dosync
   (let [f-to-data (ensure *file-to-data*)
         nodes (keys f-to-data)
         for-service (filter (fn [item]
                               (.startsWith item
                                            (str *zk-root-node* "/" service)))
                             nodes)
         high-order (sort-by major-minor-order-rev for-service)]
     (if (and high-order (not (= high-order '())))
       nil))))

(defn- lookup-services
;;   "returns nil if no services available, else returns a set of services
;; that match the required version

;; Required version is defined as:

;; list <MAJOR> - means any version where <MAJOR> part of the version matches is ok.
;; For example, if available services are:
;; (1 1 1), (1 2 1), (1 3 1) and <MAJOR> == 1, then all the services match
;; for for example (2 1 1) would not match,
;; list <MAJOR> <MINOR> - means that any version where <MAJOR> matches and
;; <MINOR> is greater than or equal to requested is ok.
;; For example, if available services are:
;; (1 1 1), (1 2 1), (1 3 1) and <MAJOR> == 1 and <MINOR> == 2,
;; then (1 2 1) and (1 3 1) matche, (2 1 1) would not match."

 [service major minor]
 (println (str "LOOKUP SERVICES: " (list service major minor))))

(defn- url-of
  [service-instance]
  (let [value (@*file-to-data* service-instance)]
    (:url value)))


(defn- lookup-service
  [service & version]
  (if (or (= '(()) version) (not (first version)))
    ;; this means the latest version major minor combo
    (let [latest (lookup-latest service)]
      (if latest
        (url-of latest)
        nil))
    (let [ver (first version)
          cnt (count ver)]
      (if (= 1 cnt)
        (lookup-services service (first ver) 0)
        (lookup-services service (first ver (second ver)))))))

(defn- mapper-fn
  "returns a string to proxy to, available parameters are obtained from the
incoming request"
  [watcher-ref scheme host port uri]
  (do
    (w/print-tree (:zipper @watcher-ref))
    (println @*file-to-data*)
    (let [parts (uri-parts uri)]
      (if (= parts [])
        ;; nil here means the 'service name part' is not in request
        nil
        (let [[service r] (get-service-from-parts parts)
              serv-tree (tz/find-path (:zipper @watcher-ref)
                                      (list "/" service))]
          (if-not serv-tree
            ;; this means the service in not registered
            nil
            ;; service at least has existed
            ;; we still might not have any currently
            ;; available

            ;; check to see if version is asked for
            (let [ver (get-version-from-parts r)
                  selected-service (lookup-service service ver)]
              (lookup-service service ver)
              (str "http://localhost:8990" "/"))))))))

(defn- initialize-watcher
  []
  (let [client (zk/connect "localhost")
        node *zk-root-node*
        w (w/watcher client node
                     (fn [dir-node] nil)
                     (fn [dir-node] nil)
                     (partial file-created client)
                     (partial file-removed client)
                     (fn [file-node data] nil))]
    (dosync (alter *route-watcher* (fn [& args] @w)))))

(defn -main
  []
  (let [server (Server.)
        connector (SelectChannelConnector.)
        handlers (HandlerCollection.)]
    (initialize-watcher)
    (. connector setPort 8888)
    (. server addConnector connector)
    (. server setHandler handlers)
    ;; setup proxy servlet
    (let [context (ServletContextHandler.
                   handlers "/" ServletContextHandler/SESSIONS)
          proxyServlet (ServletHolder.
                        (make-proxy (partial mapper-fn *route-watcher*)))
          proxy (ConnectHandler.)]
      (. context addServlet proxyServlet "/*")
      (. handlers addHandler proxy)
      (. server start))))