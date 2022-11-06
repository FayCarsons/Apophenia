(ns pfield.core
  (:require
   [pfield.shaders :as s]
   [sprog.dev.startup]
   [sprog.util :as u]
   [sprog.dom.canvas :refer [create-gl-canvas
                             maximize-canvas
                             maximize-gl-canvas
                             square-maximize-gl-canvas
                             save-image
                             set-page-background-color]]
   [sprog.webgl.shaders :refer [run-purefrag-shader!
                                run-shaders!]]
   [sprog.webgl.textures :refer [create-tex]] 
   [sprog.webgl.textures :refer [html-image-tex]]
   [sprog.input.mouse :refer [mouse-pos
                              mouse-present?]] 
   [pfield.fxhash-utils :refer [fxrand
                                fxrand-int
                                fxchance]]))

; HASHES
; 'oogXGZphqB8KbHcydiZ1nxw5RmaLdQgHjzhujSU9pf8vaVJdQ6o'

; slow opaque texture
; 'ooNZfiJSwSmb7VYjZwkh9JD2wig6XqNDp4ktp9PDt6Nqy3Qv5Hu'

; good one
; 'oo9yr3QKAgYR4e4bA5KzBxG4zwwuqQxPrEzNMyuTpP3J1JSea3A'

; based
; 'oodgK5nrjtjhZfvpM1eAJBUXDM4vj1vgnr8ikYNjczxwBx946Lq'

; HASH 11/2 migrated to most recent sprog
; 'ooGtKoig8kFD7zuyNN8MuiDWyYm91YvABzBuWzLwaq9fW1LfWzG'

; hASH 11/5
; oodEDDmAAvwJ6ua5xoN9ijdD5CdQp2VEiRumSEk5mEUhhdHYNp1

(def field-resolution (if (fxchance 0.25) 8 256))
(def particle-amount 512 #_(if (fxchance 0.5) 512 256))
(def radius (/ 1 2048))

(def img-id (str "img" (fxrand-int 1 5)))

(defonce gl-atom (atom nil))
(defonce location-texs-atom (atom nil))
(defonce field-tex-atom (atom nil))
(defonce field2-tex-atom (atom nil))
(defonce trail-texs-atom (atom nil))
(defonce html-image-atom (atom nil))

(def frame-atom (atom 0))

#_(defn rotation [angle]
  [(Math/cos angle) (- (Math/sin angle))
   (Math/sin angle) (Math/cos angle)])


(defn update-page! [] 
  (let [gl @gl-atom
        resolution [gl.canvas.width gl.canvas.height]]
    (square-maximize-gl-canvas gl {:max-pixel-ratio 2})
    (run-purefrag-shader! gl
                          s/logic-frag-source
                          particle-amount
                          {:floats {"size" [particle-amount
                                            particle-amount]
                                    "mouse" (if (mouse-present?)
                                              (mouse-pos)
                                              [0 0])}
                           :textures {"locationTex" (first @location-texs-atom)
                                      "fieldTex" @field-tex-atom
                                      "field2Tex" @field2-tex-atom}
                           :ints {"frame" @frame-atom}}
                          {:target (second @location-texs-atom)})

    (run-shaders! gl
                  [s/particle-vert-source-u16 s/particle-frag-source-f8]
                  resolution
                  {:textures {"particleTex" (first @location-texs-atom)
                              "tex" @html-image-atom}
                   :floats {"size" resolution
                            "radius" radius}
                   :ints {"frame" @frame-atom}}
                  {}
                  0
                  (* 6 particle-amount particle-amount)
                  {:target (first @trail-texs-atom)}) 

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
  (let [gl (create-gl-canvas true)]
    (reset! gl-atom gl)
    (square-maximize-gl-canvas gl) 
    
    (reset! field-tex-atom (create-tex gl :u16 field-resolution))
    (reset! field2-tex-atom (create-tex gl :u16 field-resolution))

    (reset! location-texs-atom [(create-tex gl :u16 particle-amount)
                                (create-tex gl :u16 particle-amount)])


    (reset! trail-texs-atom [(create-tex gl :f8 [gl.canvas.width gl.canvas.height])
                             (create-tex gl :f8 [gl.canvas.width gl.canvas.height])])

    (reset! html-image-atom (html-image-tex gl
                                            img-id))

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
                                       (update-page!))))
