(ns clj-jetty-proxy.ServletFilter
  (:import (java.io.IOException)
           (javax.servlet Filter FilterChain FilterConfig ServletException ServletRequest ServletResponse)
           (javax.servlet.http HttpServletRequest))
  (:gen-class))

(defn make-filter
  #^javax.servlet.Filter []
  (proxy [javax.servlet.Filter] []
    (init
      [config])
    (destroy
      [])
    (doFilter
      [request response #^javax.servlet.FilterChain filterchain]
      (do
        (println (str "FROM FILTER REQ: " request))
        (.doFilter filterchain request response)))))

#_(def pass-through-filter 
  (proxy [javax.servlet.Filter] []
    (doFilter
      [request response #^javax.servlet.FilterChain filterchain]
      (do
        (.doFilter filterchain request response)))))


#_(defn filter-chain 
  [#^javax.servlet.Servlet servlet]
    (proxy [javax.servlet.FilterChain] []
    (doFilter
      [request response]
      (.service servlet request response))))

#_(defn filtered-servlet 
  [#^javax.servlet.Filter servlet-filter handler]
  (let [#^javax.servlet.Servlet base-servlet (servlet handler)
        the-filter-chain (filter-chain base-servlet)]
    (proxy [javax.servlet.http.HttpServlet] []
        (service 
          [request response] 
          (.doFilter servlet-filter request response the-filter-chain))
      (init 
              [config] 
              (.init base-servlet config)))))

#_(defroutes my-app
  (GET "/*"
    (html 
            [:h1 "Hello Foo!!"]))
  (ANY "*"
    [404 "Page not found"])
)


#_(run-server {:port 80}
  "/*" (filtered-servlet pass-through-filter my-app))