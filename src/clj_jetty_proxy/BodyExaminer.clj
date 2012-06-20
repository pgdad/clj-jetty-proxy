(ns clj-jetty-proxy.BodyExaminer
  (:import (javax.servlet ServletInputStream)
           (java.util.Map))
  (:gen-class))

(defprotocol IExaminer
  (getServiceInfo ^java.util.Map [this ^javax.servlet.ServletInputStream stream]))

