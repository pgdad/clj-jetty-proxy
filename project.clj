(defproject clj-jetty-proxy "1.0.1"
  :description "FIXME: write description"
  :dependencies
  [[org.clojure/clojure "1.3.0"]
   [org.eclipse.jetty/jetty-servlet "8.1.2.v20120308"]
   [org.eclipse.jetty/jetty-servlets "8.1.2.v20120308"]
   [clj-zoo-watcher "1.0.3"]
   [clj-tree-zipper "1.0.0"]
   [log4j/log4j "1.2.16"]
   [org.clojure/tools.logging "0.2.3"]]
  :repl-init clj-jetty-proxy.proxyservlet
  :main clj-jetty-proxy.proxyservlet
  :aot [clj-jetty-proxy.proxyservlet])
