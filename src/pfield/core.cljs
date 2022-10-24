(ns pfield.core
  (:require [sprog.dev.startup]
            [sprog.util :as u]
            [sprog.dom.canvas :refer [create-gl-canvas
                                        square-maximize-canvas]]
            [sprog.webgl.shaders :refer [run-purefrag-shader!
                                         run-shaders!]]
            [sprog.iglu.chunks.particles :refer [particle-vert-source-u16
                                                 particle-frag-source-f8]]
            [sprog.webgl.textures :refer [create-u16-tex]]
            [sprog.iglu.chunks.noise :refer [rand-chunk
                                             simplex-2d-chunk
                                             simplex-3d-chunk
                                             get-fbm-chunk]]
            [sprog.input.mouse :refer [mouse-pos]]
            [sprog.iglu.core :refer [iglu->glsl]]))

(def iglu-wrapper
  (partial iglu->glsl
           {:max (.toFixed
                  (dec (Math/pow 2 16))
                  1)
            :octaves 3
            :hurst 0.999

            :octaves2 5
            :hurst2 0.23

            :speed 0.001

            :seed1 (rand 100)
            :seed2 (rand 100)

            :zoom (rand 100)
            :fzoom "10."
            :fzoom2 "3."}))
   
  

(def field-resolution 512)
(def particle-amount 500)
(def radius (/ 1 1024))

(defonce gl-atom (atom nil))
(defonce location-texs-atom (atom nil))
(def field-tex-atom (atom nil))

(defonce frame-atom (atom 0))

(def logic-frag-source
  (iglu-wrapper
   rand-chunk
   simplex-3d-chunk
   (get-fbm-chunk 'snoise3D 3)
   '{:version "300 es"
     :precision {float highp
                 int highp
                 usampler2D highp}
     :outputs {fragColor uvec4}
     :uniforms {size vec2
                mouse vec2
                locationTex usampler2D
                fieldTex usampler2D
                frame int}
     :signatures {main ([] void)}
     :functions
     {main
      ([]
       (=vec2 pos (/ gl_FragCoord.xy size))
       (=float time (* (float frame) ".01"))

       ; bringing u16 position tex range down to 0-1
       (=vec2 data (/ (vec2 (.xy (texture locationTex pos))) :max))
       
       ; bringing u16 flowfield tex range down to -1 1
       (=vec2 fieldData1 (- (* (/ (vec2 (.xy (texture fieldTex data)))
                             :max)
                          "2.")
                       "1."))
       
       (=vec2 fieldData2 (- (* (/ (vec2 (.zw (texture fieldTex data)))
                                  :max)
                               "2.")
                            "1."))
       
       (=vec2 field (mix fieldData1 fieldData2 mouse.x))

       (= fragColor (uvec4 (* (if (|| (> (+ data.x (* field.x :speed)) "1.")
                                      (> (+ data.y (* field.y :speed)) "1.")
                                      (< (+ data.x (* field.x :speed)) "0.")
                                      (< (+ data.y (* field.y :speed)) "0.")
                                      (> (rand (* (+ pos data) "400.")) ".99"))
                                (vec2
                                 (+ (* (fbm (vec3 (* pos :zoom) time)
                                            :octaves
                                            :hurst)
                                       ".5")
                                    ".5")
                                 (+ (* (fbm (vec3 (* pos.yx :zoom) time)
                                            :octaves
                                            :hurst)
                                       ".5")
                                    ".5"))

                                (vec2 (+ data (* field :speed)))) :max)
                           0
                           :max)))}}))


(def field-frag-source
  (iglu-wrapper
   simplex-3d-chunk
   (get-fbm-chunk 'snoise3D 3)
   '{:version "300 es"
     :precision {float highp
                 usampler2D highp}
     :uniforms {size vec2}
     :outputs {fragColor uvec4}
     :signatures {main ([] void)}
     :functions {main
                 ([]
                  (=vec2 pos (/ gl_FragCoord.xy size))
                  #_(= fragColor (uvec4 (* (vec4 (+ (* (cos (* pos.x :fzoom)) ".5") ".5")
                                               (+ (* (sin (* pos.y :fzoom)) ".5") ".5")
                                               0
                                               1)
                                         :max)))
                  
                  (= fragColor (uvec4 (* (vec4 (+ (* (fbm (vec3 (* pos :fzoom) :seed1) :octaves :hurst)
                                                     ".5")
                                                  ".5")
                                               (+ (* (fbm (vec3 (* pos.yx :fzoom) :seed1) :octaves :hurst)
                                                     ".5")
                                                  ".5")
                                               (+ (* (fbm (vec3 (* pos :fzoom2) :seed2) :octaves :hurst)
                                                     ".5")
                                                  ".5")
                                               (+ (* (fbm (vec3 (* pos.yx :fzoom2) :seed2) :octaves :hurst)
                                                     ".5")
                                                  ".5"))
                                         :max))))}}))

(def init-frag-source
  (iglu-wrapper
   rand-chunk
   simplex-3d-chunk
   (get-fbm-chunk 'snoise3D 3)
   '{:version "300 es"
     :precision {float highp
                 usampler2D highp}
     :uniforms {size vec2
                seed int}
     :outputs {fragColor uvec4}
     :signatures {main ([] void)}
     :functions {main
                 ([]
                  (=vec2 pos (/ gl_FragCoord.xy size))

                  #_(= fragColor (uvec4 (* (vec4
                                          (+ (*
                                              (fbm (vec3 (* pos :zoom) seed)
                                                   :octaves
                                                   :hurst)
                                              ".5")
                                             ".5")
                                          (+ (*
                                              (fbm (vec3 (* pos.yx :zoom) seed)
                                                   :octaves
                                                   :hurst)
                                              ".5")
                                             ".5")
                                          0
                                          1)
                                         :max)))
                  
                  (= fragColor (uvec4 (* (vec4 (rand (* pos :zoom))
                                      (rand (* pos.yx :zoom))
                                      0 
                                      1) :max))))}}))

(defn update-page! []
  (let [gl @gl-atom
        resolution [gl.canvas.width gl.canvas.height]]

    (square-maximize-canvas gl.canvas)

    (run-purefrag-shader! gl
                          logic-frag-source
                          particle-amount
                          {:floats {"size" [particle-amount
                                            particle-amount]
                                    "mouse" (mouse-pos)}
                           :textures {"locationTex" (first @location-texs-atom)
                                      "fieldTex" @field-tex-atom}
                           :ints {"frame" @frame-atom}}
                          {:target (second @location-texs-atom)})

    (run-shaders! gl
                  [particle-vert-source-u16 particle-frag-source-f8]
                  resolution
                  {:textures {"particleTex" (first @location-texs-atom)}
                   :floats {"size" (second resolution)
                            "radius" radius}}
                  {}
                  0
                  (* 6 particle-amount particle-amount))

    (swap! location-texs-atom reverse)
    (swap! frame-atom inc)
    (js/requestAnimationFrame update-page!)))

(defn init []
  (let [gl (create-gl-canvas true)
        resolution [gl.canvas.width gl.canvas.height]]
    (reset! gl-atom gl)

    (reset! field-tex-atom (create-u16-tex gl field-resolution))

    (reset! location-texs-atom [(create-u16-tex gl particle-amount)
                                (create-u16-tex gl particle-amount)])

    (run-purefrag-shader! gl
                          init-frag-source
                          particle-amount
                          {:floats {"size" [particle-amount
                                            particle-amount]}
                           :ints {"seed" (rand-int 1000)}}
                          {:target (first @location-texs-atom)})

    (run-purefrag-shader! gl
                          field-frag-source
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
