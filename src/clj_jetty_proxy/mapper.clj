(ns clj-jetty-proxy.mapper
  (:import (javax.servlet.http HttpServletRequest))
  (:require [clj-zoo-service-tracker.core :as tr]
            [clojure.tools.logging :as log]
            [clojure.data.xml :as dxml])
   (:gen-class))

(def ^:const url-serv-pattern
  (re-pattern "/([^/]*)/v([0-9]*)(\056([0-9]*))(/.*)?"))

(defn- construct-client-registrations-path
  [service client-id]
  (str "/clientregistrations/" service "/" client-id))

(def client-registrations-path (memoize construct-client-registrations-path) )

(defn- verify-client
  [registrations service client-id]
  (contains? registrations (client-registrations-path service client-id)))

(defn- headers->routing-info
  "extract routing related info from headers into a map that may be empty"
  [^HttpServletRequest request]
  (let [headers '("x-service-name" "x-client-id")
        int-headers `("x-service-version-major" "x-service-version-minor") ]
    (merge (reduce #(let [r (.getHeader request %2)]
                      (if r (assoc %1 %2 r)
                          %1)) {} headers)
           (reduce #(let [r (.getIntHeader request %2)]
                      (if-not (= -1 r) (assoc %1 %2 r)
                              %1)) {} int-headers)
           ) 
    )
  )

(defn- url->routing-info
  "extract routing related info from url into a map that may be empty
   <HOST>:<PORT>/<SERVICE>/v<VERSIONMAJOR>[.<VERSIONMINOR>]/RestOfUrl

  Where <VERSIONMINOR> is optional, and assinged to 0 if absent"
  [uri]
  (if-let [match (re-find url-serv-pattern uri)]
    (let [[_ service major _ minor path] match]
      {"x-service-name" service
       "x-service-version-major" (read-string major)
       "x-service-version-minor" (if minor (read-string minor) 0)
       :url (if path path "/")})))

(defn- sufficient-rt-info?
  "true if client-id, service-name and service-version-major keys/values exist"
  [rt-info]
  (and (contains? rt-info "x-client-id")
       (contains? rt-info "x-service-name")
       (contains? rt-info "x-service-version-major")))

(defn- extract-rt-info
  [body-ex-fun request uri]
  (let [hdr-rt-info (headers->routing-info request)]
    (if (sufficient-rt-info? hdr-rt-info)
      (assoc hdr-rt-info :url uri)
      (when-let [url-hdr-rt-info (merge (url->routing-info uri) hdr-rt-info)]
        (if (sufficient-rt-info? url-hdr-rt-info)
          url-hdr-rt-info
          (if body-ex-fun
            (merge (body-ex-fun request) url-hdr-rt-info)
            url-hdr-rt-info))))))

(defmacro srv-info-in-headers?
  [rt-info]
  `(let [m# ~rt-info]
     (and
      ;; no service related headers
      (contains? m# "x-service-name")
      (contains? m# "x-service-version-major"))))

(defmacro client-id?
  [rt-info]
  `(contains? ~rt-info "x-client-id"))

(defmacro add-default-srv-hdrs
  [rt-info]
  `(assoc ~rt-info
     "x-service-name" "_default"
     "x-service-version-major" 1))

(defmacro add-default-client-id
  [rt-info]
  `(assoc ~rt-info "x-client-id" "_default"))

(defn- add-defaults-if-needed
  [rt-info]
  (log/spy :debug (str "Adding defaults to:" rt-info
                       " IF: " (srv-info-in-headers? rt-info)))
  (let [rt-i (if-not (srv-info-in-headers? rt-info) (add-default-srv-hdrs rt-info) rt-info)]
    (log/spy :debug (str "DO I HAVE CLIENT ID in add defs: " (client-id? rt-i)))
    (if-not (client-id? rt-i)
      (add-default-client-id rt-i)
      rt-i)))

(defn req->url
  "use url path parts to determine request routing"  
  [body-ex-fun tracker-ref adder request uri]
  (let [e-rt-info (extract-rt-info body-ex-fun request uri)
        _ (log/spy :debug (str "INFO BEFORE ADD DEFAULTS: " e-rt-info))
        extracted-rt-info (add-defaults-if-needed e-rt-info)
        my-region (:my-region @tracker-ref)
        routes-multi (:routes-multi @tracker-ref)
        service (extracted-rt-info "x-service-name")
        client-id (extracted-rt-info "x-client-id")
        client-regs-ref (:client-regs-ref @tracker-ref)
        _ (println (str "CLIENT REGS REF: " client-regs-ref))
        client-regs-curator-ref (:client-regs-curator-ref @tracker-ref)
        _ (println (str "CLIENT REGS CURATOR REF: " @client-regs-curator-ref))
        _ (println (str "CLIENT REG CACHE: " @(:client-reg-cache @tracker-ref)))
        client-verified (verify-client @(:client-reg-cache @tracker-ref)
                                       service client-id)
        _ (println (str "VERIFYED CLIENT 0: " client-verified-0))
        major (extracted-rt-info "x-service-version-major")
        minor (extracted-rt-info "x-service-version-minor" 0)
        url (extracted-rt-info :url uri)]
    (log/spy :debug (str "CLIENT ID: " client-id))
    (log/spy :debug (str "CLIENT VERIFIED: " client-verified))
    (log/spy :debug (str "SERVICE NAME: " service))
    (log/spy :debug (str "SERVICE MAJOR: " major))
    (log/spy :debug (str "SERVICE MINOR: " minor))
    (log/spy :debug (str "URI: " url))
    (if-not (and service client-verified)
      ;; nil here means the 'service-name' is not in request
      ;; or client is not allowed to access service
      nil
      ;; check to see if version is asked for
      (if-let [lu (tr/lookup-service tracker-ref service major minor url client-id)]
        (do
          (adder request {:service service :major major
                          :minor minor :start (System/currentTimeMillis)})
          lu)))))




