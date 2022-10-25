(ns pfield.core
  (:require
   [pfield.shaders :as s]
   [sprog.dev.startup]
   [sprog.util :as u]
   [sprog.dom.canvas :refer [create-gl-canvas
                             maximize-canvas]]
   [sprog.webgl.shaders :refer [run-purefrag-shader!
                                run-shaders!]]
   [sprog.webgl.textures :refer [create-u16-tex
                                 create-f8-tex]] 
   [sprog.webgl.textures :refer [html-image-texture]]
   [sprog.input.mouse :refer [mouse-pos
                              mouse-present?]] 
   [pfield.fxhash-utils :refer [fxrand
                                fxrand-int]]))
   
  

(def field-resolution (* 32 (fxrand-int 16)))
(def particle-amount 512)
(def radius (/ 1 2048))

(defonce gl-atom (atom nil))
(defonce location-texs-atom (atom nil))
(defonce field-tex-atom (atom nil))
(defonce trail-texs-atom (atom nil))

(def log-atom (atom true))

(def frame-atom (atom 0))


(defn update-page! []
  (let [gl @gl-atom
        resolution [gl.canvas.width gl.canvas.height]]

    (maximize-canvas gl.canvas #_{:max-pixel-ratio 1})
    (run-purefrag-shader! gl
                          s/logic-frag-source
                          particle-amount
                          {:floats {"size" [particle-amount
                                            particle-amount]
                                    "mouse" (if (mouse-present?)
                                              (mouse-pos)
                                              [0 0])}
                           :textures {"locationTex" (first @location-texs-atom)
                                      "fieldTex" @field-tex-atom}
                           :ints {"frame" @frame-atom}}
                          {:target (second @location-texs-atom)})

    (run-shaders! gl
                  [s/particle-vert-source-u16 s/particle-frag-source-f8]
                  resolution
                  {:textures {"particleTex" (first @location-texs-atom)
                              "tex" (html-image-texture gl
                                                        "img")}
                   :floats {"size" resolution
                            "radius" radius}}
                  {}
                  0
                  (* 6 particle-amount particle-amount)
                  {:target (first @trail-texs-atom)})
    
    (when (true? @log-atom)
      (u/log resolution "when running program"))
    (reset! log-atom false)

    (run-purefrag-shader! gl
                          s/trail-frag-source
                          [gl.canvas.width gl.canvas.height]
                          {:floats {"size" [gl.canvas.width gl.canvas.height]}
                           :textures {"tex" (first @trail-texs-atom)}}
                          {:target (second @trail-texs-atom)})

    (run-purefrag-shader! gl
                          s/render-frag-source
                          [gl.canvas.width gl.canvas.height]
                          {:floats {"size" [gl.canvas.width gl.canvas.height]}
                           :textures {"tex" (first @trail-texs-atom)}})

    (swap! location-texs-atom reverse)
    (swap! trail-texs-atom reverse)
    (swap! frame-atom inc)
    (js/requestAnimationFrame update-page!)))

(defn init []
  (let [gl (create-gl-canvas true)
        resolution [gl.canvas.width gl.canvas.height]] 
    (reset! gl-atom gl)
    (maximize-canvas gl.canvas)

    (reset! field-tex-atom (create-u16-tex gl field-resolution))

    (reset! location-texs-atom [(create-u16-tex gl particle-amount)
                                (create-u16-tex gl particle-amount)]) 
    
    (u/log resolution "at tex creation")
    (reset! trail-texs-atom [(create-f8-tex gl [gl.canvas.width gl.canvas.height])
                            (create-f8-tex gl [gl.canvas.width gl.canvas.height])]) 

    (run-purefrag-shader! gl
                          s/init-frag-source
                          particle-amount
                          {:floats {"size" [particle-amount
                                            particle-amount]}
                           :ints {"seed" (rand-int 1000)}}
                          {:target (first @location-texs-atom)})

    (run-purefrag-shader! gl
                          s/field-frag-source
                          field-resolution
                          {:floats {"size" [field-resolution
                                            field-resolution]}}
                          {:target @field-tex-atom})
    (reset! frame-atom 0)))

(defn ^:dev/after-load restart! []
  (js/document.body.removeChild (.-canvas @gl-atom))
  (init))

(defn pre-init []
  (js/window.addEventListener "load" (fn [_] (init)
                                       (update-page!))))
