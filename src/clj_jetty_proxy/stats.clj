(ns clj-jetty-proxy.stats
  (:require [zookeeper :as zk]
            [clj-statsd :as s])
  (:gen-class))

(defn setup
  []
  (s/setup "127.0.0.1" 8125))

(defn- stats-metric-internal
  [service major minor]
  (str service "." major "." minor))

(defn- stats-error-internal
  [service major minor]
  (str "errors." stats-metric-internal))

(def metric (memoize stats-metric-internal))

(defn metric-from-map
  [m]
  (metric (:service m) (:major m) (:minor m)))

(def errors (memoize stats-error-internal))

(defn errors-from-map
  [m]
  (errors (:service m) (:major m) (:minor)))

(defn increment
  [m]
  (s/increment (metric-from-map m)))

(defn timing
  [m t]
  (s/timing (metric-from-map m) t))
