(ns clj-jetty-proxy.mapper
  (:import (javax.servlet.http HttpServletRequest))
  (:require [clj-zoo-service-tracker.core :as tr]
            [clojure.tools.logging :as log])
   (:gen-class))

(def ^:const url-serv-pattern
  (re-pattern "/([^/]*)/v([0-9]*)(\056([0-9]*))(/.*)?"))

(defn- verify-client
  [client-regs-ref client-id service]
  (dosync
   (let [registrations (ensure client-regs-ref)
         servs-for-client (registrations client-id)]
     (log/spy :debug (str "CLIENT REGISTRATIONS: " registrations))
     (and servs-for-client (contains? servs-for-client service)))))

(defn- headers->routing-info
  "extract routing related info from headers into a map that may be empty"
  [^HttpServletRequest request]
  (let [headers '("x-service-name" "x-client-id")
        int-headers `("x-service-version-major" "x-service-version-minor") ]
    (merge (reduce #(let [r (.getHeader request %2)]
                      (if r (assoc %1 %2 r)
                          %1)) {} headers)
           (reduce #(let [r (.getIntHeader request %2)]
                      (if (not (= -1 r)) (assoc %1 %2 r)
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
  [request uri]
  (let [hdr-rt-info (headers->routing-info request)]
    (if (sufficient-rt-info? hdr-rt-info)
      (assoc hdr-rt-info :url uri)
      (if-let [rt-info (merge (url->routing-info uri) hdr-rt-info)]
        rt-info
        nil))))

(defn req->url
  "use url path parts to determine request routing"  
  [tracker-ref request uri]
  (do
    (let [extracted-rt-info (extract-rt-info request uri)
          my-region (:my-region @tracker-ref)
          routes-multi (:routes-multi @tracker-ref)
          service (extracted-rt-info "x-service-name")
	  client-id (extracted-rt-info "x-client-id")
	  client-verified (verify-client (:client-regs-ref @tracker-ref) client-id service)
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
        (tr/lookup-service tracker-ref service major minor url client-id)))))




