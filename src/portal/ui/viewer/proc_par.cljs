(ns portal.ui.viewer.proc-par
  (:require ["dagre" :as dagre]
            ["html-to-image" :as html-to-image]
            ["react" :as react]
            ["reactflow" :as flow
             :default ReactFlow
             :refer [Background Controls Handle ReactFlowProvider applyEdgeChanges Position applyNodeChanges ControlButton]]
            [clojure.string :as str]
            [portal.resources :as io]
            [portal.ui.inspector :as ins]
            [portal.ui.styled :as s]
            [portal.ui.theme :as theme]
            [reagent.core :as r]))

(def react-flow (r/adapt-react-class ReactFlow))
(def react-flow-provider (r/adapt-react-class ReactFlowProvider))
(def background (r/adapt-react-class Background))
(def controls (r/adapt-react-class Controls))
(def flow-handle (r/adapt-react-class Handle))
(def control-button (r/adapt-react-class ControlButton))

;; inspiration
;; https://github.com/dmrd/cljs-flow/blob/main/src/cljs/app/layout.cljs
;; https://github.com/ertugrulcetin/re-frame-flow/blob/2743d17f504d518b78b8613e9a8379c0a70027bc/src/re_frame_flow/core.cljc#L203
;;

(def default-node-height 172)
(def default-node-width 768)

;;;;;
;; data munging to fit reactflow expected format
;;;;;

(def blank-pos {:x 0 :y 0})

(defn ->title [message-type-or-k]
  (try
    (str ":" (clojure.string/replace (namespace message-type-or-k) #"griffin.proc" "g.p") "/" (name message-type-or-k))
    (catch :default _err
      (str message-type-or-k))))

(defn ->message-node [m trees-children level]
  (let [rendering-data {:level         level
                        :treeRoot      m
                        :treesChildren trees-children}]
    {:id       (str (:griffin.proc/message-id m))
     ;; the real position will be added after by `add-layout` - add a blank for now
     :position blank-pos
     :type     "procMessageNode"
     :data     {:label (r/as-element [:h2 (->title (:griffin.proc/message-type m))])
                ;; this will go through clj->js and back and will break
                ;; namespaced keywords. TODO make cson/transit work instead
                :raw   rendering-data}}))

(defn ->message-edge [child parent]
  (let [parent-id (str (:griffin.proc/message-id parent))
        child-id  (str (:griffin.proc/message-id child))]

    {:id     (str "e" parent-id "-" child-id)
     :source parent-id
     :target child-id}))

(defn fill-nodes [state trees level]
  ;; recursively walk the tree
  (mapv (fn [[tree-root trees-children]]
          (let [children (map first trees-children)]
            (swap! state (fn [{:keys [nodes edges depth]}]
                           {:nodes (conj nodes (->message-node tree-root trees-children level))
                            :edges (apply conj edges (map ->message-edge children (repeat tree-root)))
                            :depth (max depth level)}))
            (fill-nodes state trees-children (inc level))))
        trees))

(defn proc-tree->init-state [tree]
  (let [state (atom {:nodes [] :edges [] :depth 0})]
    (fill-nodes state tree 0)
    @state))

;;;;;
;; Layout
;;;;;

(defn empty-dagre-graph []
  (let [Graph       (.. dagre -graphlib -Graph)
        dagre-graph (Graph.)]
    (.setDefaultEdgeLabel dagre-graph #(clj->js {}))
    (.setGraph dagre-graph (clj->js {:rankdir "TB"}))
    dagre-graph))

;; TODO maybe switch to https://github.com/d3/d3-hierarchy instead
;; https://stackoverflow.com/questions/19444293/how-to-get-the-vertically-oriented-tree-using-d3-js
;; so keep functionality out of dagre as much as possible
(defn add-layout [init-state]
  (let [dagre-graph (empty-dagre-graph)]
    (doseq [node (:nodes init-state)]
      ;; TODO make dynamic based on the actual size of the node
      ;; ex. number of keys / how nested is the map
      (.setNode dagre-graph (:id node) #js {:width  default-node-width
                                            :height default-node-height}))

    (doseq [edge (:edges init-state)]
      (.setEdge dagre-graph (:source edge) (:target edge)))

    (.layout dagre dagre-graph)

    (let [graph (update init-state :nodes (fn [nodes]
                                            (mapv (fn [node]
                                                    (let [dagre-node (.node dagre-graph (:id node))
                                                          x          (.-x dagre-node)
                                                          y          (.-y dagre-node)]
                                                      (assoc node :position {:x x
                                                                             :y y})))
                                                  nodes)))]

      {:graph          graph})))

(defn max-xy [{:keys [graph]}]
  (reduce (fn [acc {:keys [position]}]
            {:x (max (:x position) (:x acc))
             :y (max (:y position) (:y acc))})
          {:x 0 :y 0}
          (:nodes graph)))

;;;;
;; tweaks to react-flow to make it better with portal
;;;;

(defn display-proc-message-body [value]
  (let [m      (dissoc value
                       :griffin.proc/message-type ;; already in label
                       "message-type")
        _theme (theme/use-theme)]
    (println "displaying body " value)
    (fn [_props]
      [:table
       [:tbody
        [:<>
         (map-indexed
          (fn [index [k v]]
            ^{:key index}
            [:tr
             [:td (->title k)]
             [:td  (str v)]])
          (ins/try-sort-map m))]]])))

(defn guess-label-color [m]
  (let [s (try (name (or (:griffin.proc/message-type m) (get m "message-type")))
               (catch :default _err ""))]
    (cond
      (= "" s)
      "black"

      (str/ends-with? s "success")
      "green"

      (str/ends-with? s "rejected")
      "red"

      (or (str/starts-with? s "query")
          (str/starts-with? s "fetch")
          (str/ends-with? s "fetched"))
      "violet"

      :else
      "black")))

(defn custom-reactflow-proc-node [props _]
  (let [_context       (ins/use-context)
        data          (.-data props)
        decoded       (try
                        (js->clj (.-raw data))
                        (catch :default err
                          (println "could not parse back data" data err)))
        tree-root     (or (:treeRoot decoded) (get decoded "treeRoot"))
        tree-children (or (:treeChildren decoded) (get decoded "treeChildren"))
        _level         (or (:level decoded) (get decoded "level"))

        _coll    [tree-root tree-children]
        _theme   (theme/use-theme)
        node-id (flow/useNodeId)]

    ;; NOTE: the data-id of the wrapper changes all the time, causing rerender
    ;; it is the same as the default though... why?
    ;; https://reactflow.dev/docs/api/nodes/custom-nodes/
    ;; https://github.com/wbkd/react-flow/blob/893ee87838ed66ccc7d1db45f68319ec9fe042f2/packages/core/src/components/Nodes/DefaultNode.tsx#L7
    (r/as-element
     ^{:key (str "ppar-" node-id)}
     [:div {:class "proc-par"}
      ^{:key (str "ppar-target-handle-" node-id)}
      [flow-handle {:id            (str "ppar-target-handle-" node-id)
                    :type          "target"
                    :position      (or (.-targetPosition props) (.-Top Position))
                    :isConnectable (.-isConnectable props)}]
      ^{:key (str "ppar-label-" node-id)}
      [:span {:style {:color (guess-label-color tree-root)}}
       (.-label data)]
      [:f> (display-proc-message-body tree-root)]
      ^{:key (str "ppar-source-handler-" node-id)}
      [flow-handle {:id            (str "ppar-source-handler-" node-id)
                    :type          "source"
                    :position      (or (.-sourcePosition props) (.-Bottom Position))
                    :isConnectable (.-isConnectable props)}]])))

(def nodeTypes {:procMessageNode custom-reactflow-proc-node})

;;;;
;; download img button
;;;;

(defn download-btn
  "https://reactflow.dev/docs/examples/misc/download-image/
  https://reactflow.dev/docs/api/plugin-components/controls/"
  ;; NOTE: this captures the graph as seen - if the graph is to big it will be
  ;; truncated :/
  [props]
  [control-button
   {:title      "Download as PNG image"
    :aria-label "Download as PNG image"
    :on-click   (fn [_]
                  (-> (html-to-image/toPng (. js/document (querySelector ".react-flow"))
                                           #js {:filter (fn [node]
                                                          (let [excluded #{"react-flow__minimap"
                                                                           "react-flow__controls"}]

                                                            (not (some excluded (.-classList node)))))})
                      (.then (fn download-img [data-url]
                               (doto (. js/document (createElement "a"))
                                 (.setAttribute "download" (str "procparflow_" (:id props) ".png"))
                                 (.setAttribute "href" data-url)
                                 (.click))))))}
   [:div "⇓"]])

;;;;;
;; rendering
;;;;;

(def rf-style (io/inline-slurp "./node_modules/reactflow/dist/style.css"))

(defn inspect-proc-par
  ([tree] (inspect-proc-par tree false))
  ([tree simple?]
   (println "inspect-proc-par" (when simple? "simple")
            {:root (select-keys (ffirst tree) [:griffin.proc/message-type :griffin.proc/message-id])} "...")
   (let [unique-tree-id   (:griffin.proc/message-id (ffirst tree))
         initial-data     (-> tree
                              (proc-tree->init-state)
                              (add-layout))
         initial-nodes    (clj->js (:nodes (:graph initial-data)))
         initial-edges    (clj->js (:edges (:graph initial-data)))
         [nodes setNodes] (react/useState initial-nodes)
         [edges setEdges] (react/useState initial-edges)
         onNodesChange    (react/useCallback (fn [changes]
                                               (setNodes (fn [nds] (applyNodeChanges changes nds))))
                                             (into-array [setNodes]))
         onEdgesChange    (react/useCallback (fn [changes]
                                               (setEdges (fn [eds] (applyEdgeChanges changes eds))))
                                             (into-array [setEdges]))

         onInit    (react/useCallback (fn [flow-instance]
                                        (.fitView flow-instance)))
         onConnect (react/useCallback (fn [connection]
                                        (setEdges (fn [eds]
                                                    (flow/addEdge connection eds))))
                                      (into-array [setEdges]))
         bg        (ins/get-background)]
     [ins/with-default-viewer
      :portal.viewer/proc-par
      [s/div
       {:style {:height (str (+ (:y (max-xy initial-data)) default-node-height) "px")
                :width  "100%"
                :resize "height"}}
       [:style rf-style]
       [:style ".react-flow__node-procMessageNode {
    padding: 10px;
    border-radius: 3px;
    font-size: 12px;
    color: #222;
    text-align: center;
    border-width: 1px;
    border-style: solid;
    border-color: #1a192b;
    background-color: white;
}"]
       [react-flow-provider {:id unique-tree-id}
        [react-flow
         (if simple?
           {:id            unique-tree-id
            :nodes         nodes
            :edges         edges
            :onNodesChange onNodesChange
            :onEdgesChange onEdgesChange
            :onConnect     onConnect
            :onInit        onInit
            :fitView       true}

           {:id            unique-tree-id
            :nodes         nodes
            :edges         edges
            :onNodesChange onNodesChange
            :onEdgesChange onEdgesChange
            :onConnect     onConnect
            :onInit        onInit
            :fitView       true
            ;; difference here - causes re-renders in console :/
            :nodeTypes     nodeTypes})

         [controls {:id unique-tree-id}
          [download-btn {:id unique-tree-id}]]
         [background {:id    unique-tree-id
                      :style {:background bg}}]]]]])))

(defn is-proc-par-tree? [x]
  (try
    (and
     (seq x)
     (every? (fn [[tree-root trees-children]]
               (let [children (map first trees-children)]
                  ;; peek at the first level only
                 (boolean (and (:griffin.proc/message-type tree-root)
                               (every? :griffin.proc/message-type children)))))
             x))
    (catch :default err
      (println "Not a proc-par tree " err)
      false)))

(def viewer
  {:predicate is-proc-par-tree?
   :component inspect-proc-par
   :name      :portal.viewer/proc-par
   :doc       "For proc-seq/proc-par results. Takes a tree as produced by the inner implementation of proc-par"})

(defn inspect-proc-par-simple [tree]
  (inspect-proc-par tree true))

(def simple-viewer
  {:predicate is-proc-par-tree?
   ;; hack until deeper integration with portal
   :component inspect-proc-par-simple
   :name      :portal.viewer/proc-par-simple
   :doc       "For proc-seq/proc-par results. Takes a tree as produced by the inner implementation of proc-par"})
