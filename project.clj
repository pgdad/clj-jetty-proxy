(defproject clj-jetty-proxy "1.0.3"
  :description "FIXME: write description"
  :dependencies
  [[org.clojure/clojure "1.3.0"]
   [org.eclipse.jetty/jetty-servlet "8.1.3.v20120416"]
   [org.eclipse.jetty/jetty-servlets "8.1.3.v20120416"]
   [clj-zoo-watcher "1.0.5"]
   [clj-zoo-service-tracker "1.0.1"]
   [clj-tree-zipper "1.0.0"]
   [log4j/log4j "1.2.16"]
   [org.clojure/tools.logging "0.2.3"]]
  :repl-init clj-jetty-proxy.proxyservlet
  :main clj-jetty-proxy.proxyservlet
  :aot [clj-jetty-proxy.proxyservlet])
