(ns pfield.core
  (:require
   [pfield.shaders :as s]
   [sprog.dev.startup]
   [sprog.util :as u]
   [sprog.dom.canvas :refer [create-gl-canvas 
                             maximize-canvas
                             canvas-resolution
                             save-image]]
   [sprog.webgl.shaders :refer [run-purefrag-shader!
                                run-shaders!]]
   [sprog.webgl.textures :refer [create-tex
                                 html-image-tex
                                 delete-tex]]
   [sprog.iglu.chunks.misc :refer [identity-frag-source]]
   [sprog.input.mouse :refer [mouse-pos
                              mouse-present?]]
   [pfield.fxhash-utils :refer [fxrand-int
                                fxchance]]))

(def field-resolution 512)
(def particle-amount 512)
(def radius (/ 1 2048))



(defonce gl-atom (atom nil))
(defonce location-texs-atom (atom nil))
(defonce field-tex-atom (atom nil))
(defonce field2-tex-atom (atom nil))
(defonce trail-texs-atom (atom nil))
(defonce html-image-atom (atom nil))

(def frame-atom (atom 0))

(defn max-texture-size []
  (let [gl @gl-atom
        max-tex-size (.getParameter gl gl.MAX_TEXTURE_SIZE)]
    (mapv (partial min max-tex-size)
          (canvas-resolution gl))))

(defn expand-canvas []
  (let [gl @gl-atom]
    (maximize-canvas gl.canvas {:max-pixel-ratio 2})))

(defn resize-handler! [_]
  (let [gl @gl-atom]
    (expand-canvas)
    (let [resolution (max-texture-size)
          temp-texs [(create-tex gl :f8 resolution)
                     (create-tex gl :f8 resolution)]] 
      (run-purefrag-shader! gl
                            (identity-frag-source :f8)
                            resolution
                            {:floats {"size" resolution}
                             :textures {"tex" (first @trail-texs-atom)}}
                            {:target (first temp-texs)})
      (delete-tex @trail-texs-atom)
      (reset! trail-texs-atom temp-texs))))

(defn update-page! []
  (let [gl @gl-atom]
    (expand-canvas) 
    (run-purefrag-shader! gl
                          s/logic-frag-source
                          particle-amount
                          {:floats {"size" [particle-amount
                                            particle-amount]
                                    "mouse" (if (mouse-present?)
                                              (mouse-pos)
                                              [0 0])
                                    "now" (u/seconds-since-startup)}
                           :textures {"locationTex" (first @location-texs-atom)
                                      "fieldTex" @field-tex-atom
                                      "field2Tex" @field2-tex-atom}
                           :ints {"frame" @frame-atom}}
                          {:target (second @location-texs-atom)})

    (run-shaders! gl
                  [s/particle-vert-source-u16 s/particle-frag-source-f8]
                  (max-texture-size)
                  {:textures {"particleTex" (first @location-texs-atom)
                              "tex" @html-image-atom}
                   :floats {"size" (max-texture-size)
                            "radius" radius}}
                  {}
                  0
                  (* 6 particle-amount particle-amount)
                  {:target (first @trail-texs-atom)})

    (run-purefrag-shader! gl
                          s/trail-frag-source
                          (max-texture-size)
                          {:floats {"size" (max-texture-size)}
                           :textures {"tex" (first @trail-texs-atom)}}
                          {:target (second @trail-texs-atom)})
    
    (run-purefrag-shader! gl
                          s/render-frag-source
                          (max-texture-size)
                          {:floats {"size" (max-texture-size)}
                           :textures {"tex" (second @trail-texs-atom)}})
    (when (= @frame-atom 120) (js/fxpreview))

    (swap! location-texs-atom reverse)
    (swap! trail-texs-atom reverse)
    (swap! frame-atom inc)
    (js/requestAnimationFrame update-page!)))

(defn init []
  (let [gl  (create-gl-canvas true)]
    (reset! gl-atom gl)
    (expand-canvas) 
    (reset! field-tex-atom (create-tex gl :u16 field-resolution))
    (reset! field2-tex-atom (create-tex gl :u16 field-resolution))

    (reset! location-texs-atom [(create-tex gl :u16 particle-amount)
                                (create-tex gl :u16 particle-amount)])

    (reset! trail-texs-atom [(create-tex gl :f8 (max-texture-size))
                             (create-tex gl :f8 (max-texture-size))])

    (reset! html-image-atom (html-image-tex gl
                                            s/img-id))

    (run-purefrag-shader! gl
                          s/init-frag-source
                          particle-amount
                          {:floats {"size" [particle-amount
                                            particle-amount]}
                           :ints {"seed" (fxrand-int 1000)}}
                          {:target (first @location-texs-atom)})

    (run-purefrag-shader! gl
                          s/field-frag-source
                          field-resolution
                          {:floats {"size" [field-resolution
                                            field-resolution]}}
                          {:target [@field-tex-atom @field2-tex-atom]})
    (reset! frame-atom 0)))


(defn ^:dev/after-load restart! []
  (js/document.body.removeChild (.-canvas @gl-atom)) 
  (init))

(defn pre-init []
  (js/window.addEventListener "load" (fn [_] (init)
                                       (update-page!)))
  (js/window.addEventListener "resize" resize-handler!))

