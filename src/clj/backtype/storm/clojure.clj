(ns backtype.storm.clojure
  (:use [clojure.contrib.def :only [defnk defalias]])
  (:use [backtype.storm bootstrap util])
  (:import [backtype.storm StormSubmitter])
  (:import [backtype.storm.generated StreamInfo])
  (:import [backtype.storm.tuple Tuple])
  (:import [backtype.storm.task OutputCollector IBolt])
  (:import [backtype.storm.spout SpoutOutputCollector ISpout])
  (:import [backtype.storm.utils Utils])
  (:import [backtype.storm.clojure ClojureBolt ClojureSpout])
  (:require [backtype.storm [thrift :as thrift]]))

(defn hint [sym class-sym]
  (with-meta sym {:tag class-sym})
  )

(defmulti hinted-args (fn [m args] m))

(defmethod hinted-args 'prepare [_ [conf context collector]]
           [(hint conf 'java.util.Map)
            (hint context 'backtype.storm.task.TopologyContext)
            (hint collector 'backtype.storm.task.OutputCollector)]
           )

(defmethod hinted-args 'execute [_ [tuple]]
           [(hint tuple 'backtype.storm.tuple.Tuple)]
           )

(defmethod hinted-args 'cleanup [_ []]
           []
           )

(defmethod hinted-args 'close [_ []]
           []
           )

(defmethod hinted-args 'open [_ [conf context collector]]
           [(hint conf 'java.util.Map)
            (hint context 'backtype.storm.task.TopologyContext)
            (hint collector 'backtype.storm.task.OutputCollector)]
           )

(defmethod hinted-args 'nextTuple [_ []]
           []
           )

(defmethod hinted-args 'ack [_ [id]]
           [(hint id 'java.lang.Object)]
           )

(defmethod hinted-args 'fail [_ [id]]
           [(hint id 'java.lang.Object)]
           )


(defn direct-stream [fields]
  (StreamInfo. fields true))

(defn clojure-bolt* [output-spec fn-var args]
  (let [m (meta fn-var)]
    (ClojureBolt. (str (:ns m)) (str (:name m)) args (thrift/mk-output-spec output-spec))
    ))

(defmacro clojure-bolt [output-spec fn-sym args]
  `(clojure-bolt* ~output-spec (var ~fn-sym) ~args))


(defn clojure-spout* [output-spec distributed? fn-var args]
  (let [m (meta fn-var)]
    (ClojureSpout. (str (:ns m)) (str (:name m)) args (thrift/mk-output-spec output-spec) distributed?)
    ))

(defmacro clojure-spout [output-spec distributed? fn-sym args]
  `(clojure-spout* ~output-spec ~distributed? (var ~fn-sym) ~args))

(defn hint-fns [body]
  (for [[name args & impl] body
        :let [name (hint name 'void)
              args (hinted-args name args)
              args (-> "this"
                       gensym
                       (cons args)
                       vec)]]
    (concat [name args] impl)
    ))

(defmacro bolt [& body]
  (let [[bolt-fns other-fns] (split-with #(not (symbol? %)) body)
        fns (hint-fns bolt-fns)]
    `(reify IBolt
       ~@fns
       ~@other-fns)))

(defmacro bolt-execute [& body]
  `(bolt
     (~'execute ~@body)))

(defmacro spout [& body]
  (let [[spout-fns other-fns] (split-with #(not (symbol? %)) body)
        fns (hint-fns spout-fns)]
    `(reify ISpout
       ~@fns
       ~@other-fns)))

(defmacro defbolt [name output-spec & [opts & impl :as all]]
  (if-not (map? opts)
    `(defbolt ~name ~output-spec {} ~@all)
    (let [worker-name (symbol (str name "__"))
          params (:params opts)
          fn-body (if (:prepare opts)
                    (cons 'fn impl)
                    (let [[args & impl-body] impl
                          coll-sym (nth args 1)
                          args (vec (take 1 args))
                          prepargs (hinted-args 'prepare [(gensym "conf") (gensym "context") coll-sym])]
                      `(fn ~prepargs (bolt (~'execute ~args ~@impl-body)))))
          definer (if params
                    `(defn ~name [& args#]
                       (clojure-bolt ~output-spec ~worker-name args#))
                    `(def ~name
                       (clojure-bolt ~output-spec ~worker-name []))
                    )
          ]
      `(do
         (defn ~worker-name ~(if params params [])
           ~fn-body
           )
         ~definer
         ))))

(defmacro defspout [name output-spec & [opts & impl :as all]]
  (if-not (map? opts)
    `(defspout ~name ~output-spec {} ~@all)
    (let [worker-name (symbol (str name "__"))
          params (:params opts)
          distributed? (get opts :distributed true)
          prepare? (:prepare opts)
          prepare? (if (nil? prepare?) true prepare?)
          fn-body (if prepare?
                    (cons 'fn impl)
                    (let [[args & impl-body] impl
                          coll-sym (first args)
                          prepargs (hinted-args 'open [(gensym "conf") (gensym "context") coll-sym])]
                      `(fn ~prepargs (spout (~'nextTuple [] ~@impl-body)))))
          definer (if params
                    `(defn ~name [& args#]
                       (clojure-spout ~output-spec ~distributed? ~worker-name args#))
                    `(def ~name
                       (clojure-spout ~output-spec ~distributed? ~worker-name []))
                    )
          ]
      `(do
         (defn ~worker-name ~(if params params [])
           ~fn-body
           )
         ~definer
         ))))

(defprotocol TupleValues
  (tuple-values [values collector stream]))

(extend-protocol TupleValues
  java.util.Map
  (tuple-values [this collector ^String stream]
    (let [ fields (.. (:context collector) (getThisOutputFields stream) toList) ]
      (vec (map (into 
                  (empty this) (for [[k v] this] 
                                   [(if (keyword? k) (name k) k) v])) 
                fields))))
  java.util.List
  (tuple-values [this collector stream]
    this))

(defnk emit-bolt! [collector ^TupleValues values
                   :stream Utils/DEFAULT_STREAM_ID :anchor []]
  (let [^List anchor (collectify anchor)
        values (tuple-values values collector stream) ]
    (.emit (:output-collector collector) stream anchor values)
    ))

(defnk emit-direct-bolt! [collector task ^TupleValues values
                          :stream Utils/DEFAULT_STREAM_ID :anchor []]
  (let [^List anchor (collectify anchor)
        values (tuple-values values collector stream) ]
    (.emitDirect (:output-collector collector) task stream anchor values)
    ))

(defn ack! [collector ^Tuple tuple]
  (.ack (:output-collector collector) tuple))

(defn fail! [collector ^Tuple tuple]
  (.fail (:output-collector collector) tuple))

(defnk emit-spout! [collector ^TupleValues values
                    :stream Utils/DEFAULT_STREAM_ID :id nil]
  (let [values (tuple-values values collector stream)]
    (.emit (:output-collector collector) stream values id)))

(defnk emit-direct-spout! [collector task ^TupleValues values
                           :stream Utils/DEFAULT_STREAM_ID :id nil]
  (let [values (tuple-values values collector stream)]
    (.emitDirect (:output-collector collector) task stream values id)))

(defalias topology thrift/mk-topology)
(defalias bolt-spec thrift/mk-bolt-spec)
(defalias spout-spec thrift/mk-spout-spec)
(defalias shell-bolt-spec thrift/mk-shell-bolt-spec)

(defn submit-remote-topology [name conf topology]
  (StormSubmitter/submitTopology name conf topology))

(defn local-cluster []  
  ;; do this to avoid a cyclic dependency of
  ;; LocalCluster -> testing -> nimbus -> bootstrap -> clojure -> LocalCluster
  (eval '(new backtype.storm.LocalCluster)))
