(ns pfield.shaders
  (:require [sprog.util :as u]
            [sprog.iglu.chunks.noise :refer [simplex-3d-chunk
                                             get-fbm-chunk
                                             rand-chunk]]
            [sprog.iglu.core :refer [iglu->glsl]]
            [pfield.fxhash-utils :refer [fxrand
                                         fxrand-int]])) 

(def global-shader-keywords {:max (.toFixed
                                    (dec (Math/pow 2 16))
                                    1)
                              :TAU (.toFixed u/TAU 12)
                              :PI (.toFixed Math/PI 12)
                              :TWO_PI (.toFixed (* Math/PI 2) 12)
                              :HALF_PI (.toFixed (* Math/PI 0.5) 12)

                              :octaves (+ 2 (fxrand-int 8))
                              :hurst (fxrand)

                              :octaves2 (+ 2 (fxrand-int 8))
                              :hurst2 (fxrand)

                              :speed (.toFixed (fxrand 0.0005 0.002) 12)
                              :fade (.toFixed (+ 0.9 (fxrand 0.0999)) 12)

                              :seed1 (fxrand 1000)
                              :seed2 (fxrand 1000)

                              :off1 (fxrand 50)
                              :off2 (fxrand 50)

                              :zoom "100.0"

                              :fzoom  (.toFixed (fxrand 0.5 3) 12)
                              :fzoom2 (.toFixed (fxrand 0.5 3) 12)})

(def iglu-wrapper
  (partial iglu->glsl
           (u/log-tables global-shader-keywords)))

(def render-frag-source
  (iglu-wrapper
   '{:version "300 es"
     :precision {float highp
                 int highp
                 sampler2D highp}
     :uniforms {size vec2
                tex sampler2D}
     :outputs {fragColor vec4}
     :signatures {main ([] void)}
     :functions
     {main
      ([]
       (=vec2 pos (/ gl_FragCoord.xy size)) 
       (= fragColor (texture tex pos)))}}))

(def trail-frag-source
  (iglu-wrapper
   '{:version "300 es"
     :precision {float highp
                 int highp
                 sampler2D highp}
     :uniforms {size vec2
                tex sampler2D}
     :outputs {fragColor vec4}
     :signatures {main ([] void)}
     :functions
     {main
      ([]
       (=vec2 pos (/ gl_FragCoord.xy size))
       (= fragColor (* (vec4 (texture tex pos)) :fade)))}}))

(def particle-vert-source-u16
  (iglu-wrapper
   '{:version "300 es"
     :precision {float highp
                 int highp
                 usampler2D highp}
     :outputs {particlePos vec2
               v_color vec2}
     :uniforms {particleTex usampler2D
                radius float}
     :signatures {main ([] void)}
     :functions
     {main
      ([]
       (=int agentIndex (/ gl_VertexID 6))
       (=int corner "gl_VertexID % 6")

       (=ivec2 texSize (textureSize particleTex 0))

       (=vec2 texPos
              (/ (+ "0.5" (vec2 (% agentIndex texSize.x)
                                (/ agentIndex texSize.x)))
                 (vec2 texSize)))

       (=uvec4 particleColor (texture particleTex texPos))
       (= particlePos (/ (vec2 particleColor.xy) "65535.0"))

       (= v_color (vec2 (/ (vec2 (.zw (texture particleTex texPos))) :max)))

       (= gl_Position
          (vec4 (- (* (+ particlePos
                         (* radius
                            (- (* "2.0"
                                  (if (|| (== corner 0)
                                          (== corner 3))
                                    (vec2 0 1)
                                    (if (|| (== corner 1)
                                            (== corner 4))
                                      (vec2 1 0)
                                      (if (== corner 2)
                                        (vec2 0 0)
                                        (vec2 1 1)))))
                               "1.0")))
                      "2.0")
                   "1.0")
                0
                1)))}}))

(def particle-frag-source-f8
  (iglu-wrapper
   rand-chunk
   '{:version "300 es"
     :precision {float highp
                 int highp
                 sampler2D highp}
     :uniforms {radius float
                size vec2
                tex sampler2D}
     :inputs {particlePos vec2
              v_color vec2}
     :outputs {fragColor vec4}
     :signatures {main ([] void)}
     :functions
     {main
      ([]
       (=vec2 pos (/ gl_FragCoord.xy size))
       (=float dist (distance pos particlePos))
       ("if" (> dist radius)
             "discard")
       (= fragColor (texture tex (vec2 0 (/ (+ (atan (/ v_color.y v_color.x)) :HALF_PI) :PI))
                             #_(+ particlePos (vec2 (* (cos (* particlePos.x :off1)) ".2")
                                                  (* (sin (* particlePos.y :off1)) ".2")))
                             #_(vec2 (/ "1." (.x (vec2 (textureSize tex 0))))
                                     (/ particlePos.y "1.")))))}}))

(def logic-frag-source
  (iglu-wrapper
   rand-chunk
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
                                      (> (rand (* (+ pos data) "400.")) ".995"))
                                (vec2
                                 (rand (+ (* pos :off1) time))
                                 (rand (+ (* pos :off2) time)))

                                (vec2 (+ data (* field :speed)))) :max)

                            (* (+ (* field ".5") ".5") :max))))}}))


(def field-frag-source
  (iglu-wrapper
   simplex-3d-chunk
   (get-fbm-chunk 'snoise3D 3)
   '{:version "300 es"
     :precision {float highp
                 usampler2D highp}
     :uniforms {size vec2
                u_rotate mat2}
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
