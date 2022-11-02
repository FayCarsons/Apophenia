(ns pfield.shaders
  (:require [sprog.util :as u]
            [sprog.iglu.chunks.noise :refer [simplex-3d-chunk
                                             get-fbm-chunk
                                             rand-chunk]]
            [sprog.iglu.chunks.misc :refer [rescale-chunk]]
            [sprog.iglu.chunks.postprocessing :refer [get-bloom-chunk
                                                      star-neighborhood]]
            [sprog.iglu.core :refer [iglu->glsl]]
            [pfield.fxhash-utils :refer [fxrand
                                         fxrand-int]])) 

(def global-shader-keywords {:max (dec (Math/pow 2 16))
                                   
                             :TAU u/TAU 
                             :PI Math/PI
                             :TWO_PI (* Math/PI 2)
                             :HALF_PI (* Math/PI 0.5)

                             :octaves (str (+ 2 (fxrand-int 20)))
                             :hurst (fxrand 1.5)

                             :octaves2 (str (+ 2 (fxrand-int 20)))
                             :hurst2 (fxrand 1.5)

                             :speed  (fxrand 0.001 0.005) 
                             :acc 0.005 
                             :damp (fxrand 0.98 0.99) 

                             :fade (fxrand 0.95 0.9999)

                             :color-offset (fxrand 1 3)

                             :time-factor 0.0001

                             :seed1 (fxrand 10000)
                             :seed2 (fxrand 10000)
                             :seed3 (fxrand 10000)
                             :seed4 (fxrand 10000)

                             :off1 (fxrand 50)
                             :off2 (fxrand 50)

                             :zoom 100

                             :fzoom  (fxrand 0.5 5 0.75)
                             :fzoom2 (fxrand 0.5 5 0.4)

                             :step (/ 1 512)
                             :intensity (fxrand 0.4 0.8 0.6)})

(u/log-tables global-shader-keywords)

(def iglu-wrapper
  (partial iglu->glsl
            global-shader-keywords))

(def render-frag-source
  (iglu-wrapper
   (get-bloom-chunk :f8 (star-neighborhood 4) 0.5)
   '{:version "300 es"
     :precision {float highp
                 int highp
                 sampler2D highp}
     :uniforms {size vec2
                tex sampler2D
                u_rotate vec2}
     :outputs {fragColor vec4}
     :main ((=vec2 pos (/ gl_FragCoord.xy size))
            (= fragColor (bloom tex pos :step :intensity)))}))

(def trail-frag-source
  (iglu-wrapper
   '{:version "300 es"
     :precision {float highp
                 int highp
                 sampler2D highp}
     :uniforms {size vec2
                tex sampler2D}
     :outputs {fragColor vec4}
     :main
     ((=vec2 pos (/ gl_FragCoord.xy size))
      (= fragColor (* (vec4 (texture tex pos)) :fade)))}))

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
     :main ((=int agentIndex (/ gl_VertexID "6"))
            (=int corner "gl_VertexID % 6")

            (=ivec2 texSize (textureSize particleTex i0))

            (=vec2 texPos
                   (/ (+ 0.5 (vec2 (% agentIndex texSize.x)
                                   (/ agentIndex texSize.x)))
                      (vec2 texSize)))

            (=uvec4 particleColor (texture particleTex texPos))
            (= particlePos (/ (vec2 particleColor.xy) 65535))

            (= v_color (vec2 (/ (vec2 (.zw (texture particleTex texPos))) :max)))

            (= gl_Position
               (vec4 (- (* (+ particlePos
                              (* radius
                                 (- (* 2
                                       (if (|| (== corner "0")
                                               (== corner "3"))
                                         (vec2 0 1)
                                         (if (|| (== corner "1")
                                                 (== corner "4"))
                                           (vec2 1 0)
                                           (if (== corner "2")
                                             (vec2 0 0)
                                             (vec2 1 1)))))
                                    1)))
                           2)
                        1)
                     0
                     1)))}))

(def particle-frag-source-f8
  (iglu-wrapper
   rand-chunk
   '{:version "300 es"
     :precision {float highp
                 int highp
                 sampler2D highp}
     :uniforms {radius float
                size vec2
                tex sampler2D
                frame int}
     :inputs {particlePos vec2
              v_color vec2}
     :outputs {fragColor vec4}
     :main
     ((=vec2 pos (/ gl_FragCoord.xy size))
      (=float time (* (float frame) :time-factor))
      (=float dist (distance pos particlePos))
      ("if" (> dist radius)
            "discard")
      (= fragColor (texture tex
                            (vec2 :color-offset (/ (* (atan (- (* v_color 2) 1))
                                        :PI)
                                     :PI)))))}))

(def logic-frag-source
  (iglu-wrapper
   rand-chunk
   rescale-chunk
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
     :main
     ((=vec2 pos (/ gl_FragCoord.xy size))
      (=float time (* (float frame) .01))

       ; bringing u16 position tex range down to 0-1
      (=vec2 particlePos (/ (vec2 (.xy (texture locationTex pos))) :max))

       ; bringing u16 flowfield tex range down to -1 1
      (=vec2 fieldData1 (- (* (/ (vec2 (.xy (texture fieldTex particlePos)))
                                 :max)
                              2)
                           1))

      (=vec2 fieldData2 (- (* (/ (vec2 (.zw (texture fieldTex particlePos)))
                                 :max)
                              2)
                           1))

      (=vec2 field (mix fieldData1 fieldData2 mouse.x))

      (=vec2 particleVelocity (/ (vec2 (.zw (texture locationTex pos))) :max))
      (= particleVelocity (- (* particleVelocity 2) 1))
      (+= particleVelocity (* field :acc))
      (*= particleVelocity :damp)



      (= fragColor (uvec4 (* (vec4 (+ particlePos (* particleVelocity :speed))

                                   (+ (* particleVelocity 0.5) 0.5)) :max)))

      ("if" (|| (> (+ particlePos.x (* field.x :speed)) 1)
                (> (+ particlePos.y (* field.y :speed)) 1)
                (< (+ particlePos.x (* field.x :speed)) 0)
                (< (+ particlePos.y (* field.y :speed)) 0)
                (> (rand (* (+ pos particlePos) 400)) 0.99))
            (= fragColor (uvec4 (* (vec4
                                    (rand (+ (* pos :zoom) time))
                                    (rand (+ (* pos.yx :zoom) time))
                                    0.5
                                    0.5) :max)))))}))


(def field-frag-source
  (iglu-wrapper
   simplex-3d-chunk
   (get-fbm-chunk 'snoise3D 3)
   '{:version "300 es"
     :precision {float highp
                 usampler2D highp}
     :uniforms {size vec2}
     :outputs {fragColor uvec4} 
     :main ((=vec2 pos (/ gl_FragCoord.xy size))
            (=float angle (* :TAU -0.125))
            (=mat2 rotation  (mat2 (cos angle) (- 0 (sin angle))
                                   (sin angle) (cos angle)))
            (= pos (- (* pos 2) 1))
            (*= pos rotation)
            (= pos (* 0.5 (+ 1 pos)))

            (=vec4 fieldValue
                   (vec4 (+ (* (fbm (vec3 (* pos :fzoom) :seed1) 
                                    :octaves 
                                    :hurst)
                               0.5)
                            0.5)
                         (+ (* (fbm (vec3 (* pos.yx :fzoom) :seed1) 
                                    :octaves 
                                    :hurst)
                               0.5)
                            0.5)
                         (+ (* (fbm (vec3 (* pos :fzoom2) :seed2) 
                                    :octaves2 
                                    :hurst2)
                               0.5)
                            0.5)
                         (+ (* (fbm (vec3 (* pos.yx :fzoom2) :seed2) 
                                    :octaves2 
                                    :hurst2)
                               0.5)
                            0.5)))
            
            #_(=vec4 fieldValue (vec4 1 0 0 1))

            (= fieldValue (-  (* fieldValue 2) 1))
            (*= fieldValue.xy  (inverse rotation))
            (*= fieldValue.zw  (inverse rotation))
            (= fieldValue (* (+ fieldValue 1) 0.5))

            (= fragColor (uvec4 (* fieldValue :max))))}))

(def init-frag-source
  (iglu-wrapper
   rand-chunk
   '{:version "300 es"
     :precision {float highp
                 usampler2D highp}
     :uniforms {size vec2
                seed int}
     :outputs {fragColor uvec4} 
     :main ((=vec2 pos (/ gl_FragCoord.xy size)) 

                  (= fragColor (uvec4 (* (vec4 (rand (* pos :zoom))
                                               (rand (* pos.yx :zoom))
                                               0.5
                                               0.5) :max))))}))
