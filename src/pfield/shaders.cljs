(ns pfield.shaders
  (:require [sprog.util :as u]
            [sprog.iglu.chunks.noise :refer [simplex-3d-chunk
                                             get-fbm-chunk
                                             rand-chunk]]
            [sprog.iglu.chunks.misc :refer [rescale-chunk
                                            sympow-chunk]]
            [sprog.iglu.chunks.postprocessing :refer [get-bloom-chunk
                                                      star-neighborhood
                                                      square-neighborhood
                                                      plus-neighborhood
                                                      create-gaussian-sample-chunk]]
            [sprog.iglu.core :refer [iglu->glsl]]
            [pfield.fxhash-utils :refer [fxrand
                                         fxrand-int
                                         fxchance
                                         fxshuffle]])) 

(def zoom-array (fxshuffle (map #(/ % 2) 
                                (map inc (range 10)))))

(def global-shader-keywords {:max (dec (Math/pow 2 16))

                             :TAU u/TAU
                             :PI Math/PI
                             :TWO_PI (* Math/PI 2)
                             :HALF_PI (* Math/PI 0.5)

                             :octaves (str (+ 2 (fxrand-int 20)))
                             :hurst (fxrand 1.5)

                             :octaves2 (str (+ 2 (fxrand-int 20)))
                             :hurst2 (fxrand 1.5)

                             :octaves3 (str (+ 2 (fxrand-int 20)))
                             :hurst3 (fxrand 1.5)

                             :octaves4 (str (+ 2 (fxrand-int 20)))
                             :hurst4 (fxrand 1.5)

                             :speed  (fxrand 0.001 0.006)
                             :acc 0.005
                             :damp (fxrand 0.98 0.995 0.6)

                             :fade (fxrand 0.95 0.9999)

                             :color-offset (fxrand 0.1 1)
                             :color-mode (str (fxchance 0.33))
                             :choose-field (str (fxchance 0.5))

                             :time-factor 0.0001

                             :seed1 (fxrand 100000)
                             :seed2 (fxrand 100000)
                             :seed3 (fxrand 100000)
                             :seed4 (fxrand 100000)

                             :off1 (fxrand 50)
                             :off2 (fxrand 50)

                             :zoom (fxrand 100 100000)
                             :zoom2 (fxrand 100 100000)

                             :fzoom  (fxrand 0.5 (nth zoom-array 0))
                             :fzoom2 (fxrand 0.5 (nth zoom-array 1))
                             :fzoom3  (fxrand 0.5 (nth zoom-array 2))
                             :fzoom4 (fxrand 0.5 (nth zoom-array 3))

                             :step (/ 1 256)
                             :intensity (fxrand 0.7 0.95 0.6)})

#_(u/log-tables global-shader-keywords)
(u/log zoom-array)

(def iglu-wrapper
  (partial iglu->glsl
            global-shader-keywords))

(def render-frag-source
  (iglu-wrapper
   (get-bloom-chunk :f8 (star-neighborhood 24 3) 0.999) 
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
      (= fragColor (* (vec4(texture tex pos)) :fade)))}))

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
      (=float y_vel (- (* v_color.y 2) 1))
      (=float x_vel (- (* v_color.x 2) 1))
      (=vec2 v1 (vec2 :color-offset (+ (/ (atan (/ y_vel x_vel))
                     :TAU)
                  0.5)))
      (=vec2 v2 (vec2 (/ (* (atan (- (* v_color 2) 1)) :PI) :PI)))
      (= fragColor (texture tex
                            (if :color-mode v1 v2))))}))

(def logic-frag-source
  (iglu-wrapper
   rand-chunk 
   sympow-chunk
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
                field2Tex usampler2D
                frame int}
     :main
     ((=vec2 pos (/ gl_FragCoord.xy size))
      (=float time (* (float frame) 0.005))



       ; bringing u16 position tex range down to 0-1
      (=vec2 particlePos (/ (vec2 (.xy (texture locationTex pos))) :max))

      (=vec4 rawField (vec4 (texture fieldTex particlePos)))
      (=vec4 rawField2 (vec4 (texture field2Tex particlePos)))


       ; bringing u16 flowfield tex range down to -1 1
      (=vec2 fieldData1 (- (* (/ (vec2 (.xy rawField))
                                 :max)
                              2)
                           1))

      (=vec2 fieldData2 (- (* (/ (vec2 (.zw rawField))
                                 :max)
                              2)
                           1))
      (=vec2 fieldData3 (- (* (/ (vec2 (.xy rawField2))
                                 :max)
                              2)
                           1))

      (=vec2 fieldData4 (- (* (/ (vec2 (.zw rawField2))
                                 :max)
                              2)
                           1))
      
      (=float subfade (rescale -1 1 0 1 (sympow (sin (* time 0.5)) 0.2)))
      (=vec2 subfield (mix fieldData1 fieldData2 subfade))
      (=vec2 subfield2 (mix fieldData3 fieldData4 subfade))

      (=float leadfade (rescale -1 1 0 1 (sympow (sin (* time 0.25)) 0.1)))
      (=vec2 field (mix subfield subfield2 leadfade))

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
                                    (rand (+ (* pos.yx :zoom2) time))
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
     :outputs {fragColor uvec4
               fragColor1 uvec4}
    :qualifiers {fragColor "layout(location=0)"
                 fragColor1 "layout(location=1)"} 
     :main ((=vec2 pos (/ gl_FragCoord.xy size))
            
            ; creating rotation matrix
            (=float angle (* :TAU -0.125))
            (=mat2 rotation  (mat2 (cos angle) (- 0 (sin angle))
                                   (sin angle) (cos angle)))
            ; rotating pixel position
            (= pos (- (* pos 2) 1))
            (*= pos rotation)
            (= pos (* 0.5 (+ 1 pos)))
            
            ; creating 4 2D/xy flowfields w/ FBM
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

            (=vec4 field2Value
                   (vec4 (+ (* (fbm (vec3 (* pos :fzoom3) :seed3)
                                    :octaves3
                                    :hurst3)
                               0.5)
                            0.5)
                         (+ (* (fbm (vec3 (* pos.yx :fzoom3) :seed3)
                                    :octaves3
                                    :hurst3)
                               0.5)
                            0.5)
                         (+ (* (fbm (vec3 (* pos :fzoom4) :seed4)
                                    :octaves4
                                    :hurst4)
                               0.5)
                            0.5)
                         (+ (* (fbm (vec3 (* pos.yx :fzoom4) :seed4)
                                    :octaves4
                                    :hurst4)
                               0.5)
                            0.5)))

            (= fieldValue (-  (* fieldValue 2) 1))
            (*= fieldValue.xy  (inverse rotation))
            (*= fieldValue.zw  (inverse rotation))
            (= fieldValue (* (+ fieldValue 1) 0.5))

            (= field2Value (- (* field2Value 2) 1))
            (*= field2Value.xy (inverse rotation))
            (*= field2Value.zw (inverse rotation))
            (= field2Value (* (+ field2Value 1) 0.5))

            (= fragColor (uvec4 (* fieldValue :max)))
            (= fragColor1 (uvec4 (* field2Value :max))))}))

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
