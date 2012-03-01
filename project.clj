(defproject clj-jetty-proxy "1.0.0"
  :description "FIXME: write description"
  :dependencies
  [[org.clojure/clojure "1.3.0"]
   [org.eclipse.jetty/jetty-servlet "8.0.4.v20111024"]
   [org.eclipse.jetty/jetty-servlets "8.0.4.v20111024"]]
  :repl-init clj-jetty-proxy.proxyservlet
  :aot [clj-jetty-proxy.proxyservlet])
