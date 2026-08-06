package com.wetinknext.engine.gl

object ShaderLib {
    const val compositorVertex="""#version 300 es
        layout(location=0) in vec2 aPosition;uniform mat4 uCanvasToClip;uniform vec2 uCanvasSize;out vec2 vUv;void main(){vUv=aPosition/uCanvasSize;gl_Position=uCanvasToClip*vec4(aPosition,0.,1.);}"""
    const val compositorFragment = """#version 300 es
        precision highp float;

        in vec2 vUv;
        uniform sampler2D uLayerTex;
        uniform sampler2D uStrokeTex;
        uniform int uStrokeActive;
        uniform float uOpacity;

        out vec4 fragColor;

        vec3 linearToSrgb(vec3 color) {
            vec3 c = clamp(color, 0.0, 1.0);
            vec3 low = c * 12.92;
            vec3 high = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
            return mix(low, high, step(vec3(0.0031308), c));
        }

        vec3 unpremultiply(vec4 color) {
            return color.a <= 0.0001 ? vec3(0.0) : color.rgb / color.a;
        }

        void main() {
            vec4 layer = texture(uLayerTex, vUv);
            if (uStrokeActive == 1) {
                vec4 stroke = texture(uStrokeTex, vUv);
                layer = stroke + layer * (1.0 - stroke.a);
            }

            float alpha = layer.a * uOpacity;
            fragColor = vec4(linearToSrgb(unpremultiply(layer)) * alpha, alpha);
        }
    """
    const val dabVertex = """#version 300 es
        layout(location = 0) in vec2 aCorner;
        layout(location = 1) in vec4 iDab0;
        layout(location = 2) in float iAlpha;
        uniform mat4 uCanvasToClip;
        out vec2 vLocal; flat out float vAlpha;
        void main(){ float c=cos(iDab0.w), s=sin(iDab0.w); vec2 r=vec2(c*aCorner.x-s*aCorner.y,s*aCorner.x+c*aCorner.y); vLocal=aCorner;vAlpha=iAlpha;gl_Position=uCanvasToClip*vec4(iDab0.xy+r*iDab0.z,0.,1.); }
    """
    const val dabFragment = """#version 300 es
        precision highp float; in vec2 vLocal; flat in float vAlpha; uniform vec3 uColorLinear; out vec4 fragColor;
        void main(){ float r=length(vLocal), aa=max(fwidth(r),.001), a=vAlpha*(1.-smoothstep(1.-aa,1.+aa,r));fragColor=vec4(uColorLinear*a,a); }
    """
    const val strokeCompositeFragment = """#version 300 es
        precision highp float; in vec2 vUv; uniform sampler2D uCanvasTex; uniform sampler2D uStrokeTex; uniform int uStrokeActive; out vec4 fragColor;
        vec3 toSrgb(vec3 c){c=clamp(c,0.,1.);return mix(c*12.92,1.055*pow(c,vec3(1./2.4))-.055,step(vec3(.0031308),c));}
        vec3 unp(vec4 c){return c.a<=.0001?vec3(0.):c.rgb/c.a;}
        void main(){vec4 c=texture(uCanvasTex,vUv);vec4 s=texture(uStrokeTex,vUv);float a=uStrokeActive==1?s.a+c.a*(1.-s.a):c.a;vec3 rgb=uStrokeActive==1&&a>.0001?(unp(s)*s.a+unp(c)*c.a*(1.-s.a))/a:unp(c);fragColor=vec4(toSrgb(rgb),1.);}
    """
    const val canvasPresentVertex = """#version 300 es
        layout(location = 0) in vec2 aPosition;
        uniform mat4 uCanvasToClip;
        uniform vec2 uCanvasSize;
        out vec2 vUv;
        void main() {
            vUv = aPosition / uCanvasSize;
            gl_Position = uCanvasToClip * vec4(aPosition, 0.0, 1.0);
        }
    """
    const val presentFragment = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uTexture;
        out vec4 fragColor;
        void main() { fragColor = texture(uTexture, vUv); }
    """
    const val fullscreenVertex = """#version 300 es
        layout(location = 0) in vec2 aPosition;
        out vec2 vUv;
        void main() { vUv = aPosition * 0.5 + 0.5; gl_Position = vec4(aPosition, 0.0, 1.0); }
    """
    const val solidFragment = """#version 300 es
        precision highp float;
        in vec2 vUv;
        out vec4 fragColor;
        void main() { fragColor = vec4(vUv, 0.0, 1.0); }
    """
}
