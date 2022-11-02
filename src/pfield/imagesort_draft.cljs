(ns pfield.imagesort-draft
  (:require
   [sprog.webgl.textures :refer [html-image-texture
                                 create-f8-tex]]
   [sprog.webgl.shaders :refer [run-purefrag-shader!]]
   [sprog.dom.canvas :refer [create-gl-canvas
                             save-image]]))
    
    (let [gl (create-gl-canvas)
          image (html-image-texture gl "img")
          fb (.createFramebuffer gl)
          pixels (js/Uint8Array. (* 1024 4))]
      (.bindFramebuffer gl gl.FRAMEBUFFER fb)
      (.framebufferTexture2D gl gl.FRAMEBUFFER gl.COLOR_ATTACHMENT0
                             gl.TEXTURE_2D image 0)
      (.readPixels gl 0 0 1 1024 gl.RGBA gl.UNSIGNED_BYTE pixels)
      (let [data (flatten (sort-by (fn [[R G B]] (/ (+ R G B) 3)) (partition 4 pixels)))
            u8array (js/Uint8Array. data)
            tex (create-f8-tex gl [1 1024] {:data u8array})]
        (u/log
         (take 5 data)
         (take 5 (reverse data)))
        (set! gl.canvas.width  1)
        (set! gl.canvas.height 1024)
        (run-purefrag-shader! gl '{:version "300 es"
                                   :precision {float highp
                                               sampler2D highp}
                                   :uniforms {tex sampler2D
                                              size vec2}
                                   :outputs {fragColor vec4}
                                   :main ((=vec2 pos (/ gl_FragCoord.xy size))
                                          (= fragColor (vec4 (.xyz (texture tex pos))
                                                             1)))}
                              [gl.canvas.width gl.canvas.height]
                              {:textures {"tex" tex}
                               :floats {"size" [gl.canvas.width gl.canvas.height]}})
        (js/console.log u8array)
        (save-image gl.canvas "sorted1D")))