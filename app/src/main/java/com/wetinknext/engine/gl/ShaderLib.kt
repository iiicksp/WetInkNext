package com.wetinknext.engine.gl

object ShaderLib {
    const val compositorVertex="""#version 300 es
        layout(location=0) in vec2 aPosition;
        uniform mat4 uCanvasToClip;
        uniform vec2 uCanvasSize;
        uniform vec4 uUvRect;
        out vec2 vUv;
        out vec2 vCanvasUv;
        out vec2 vScreenUv;
        void main(){
            vCanvasUv=aPosition/uCanvasSize;
            vUv=mix(uUvRect.xy,uUvRect.zw,vCanvasUv);
            gl_Position=uCanvasToClip*vec4(aPosition,0.,1.);
            vScreenUv=gl_Position.xy*.5+.5;
        }"""
    const val compositorFragment = """#version 300 es
        precision highp float;
        in vec2 vUv;
        in vec2 vCanvasUv;
        in vec2 vScreenUv;
        uniform sampler2D uLayerTex;
        uniform sampler2D uStrokeTex;
        uniform sampler2D uScreenStrokeTex;
        uniform sampler2D uStrokeCoverageTex;
        uniform int uStrokeActive;
        uniform int uStrokeIsScreenSpace;
        uniform int uStrokeMode;
        uniform int uStrokeErase;
        uniform float uOpacity;
        uniform float uStrokeOpacity;
        uniform vec3 uStrokeColorLinear;
        out vec4 fragColor;
        void main() {
            vec4 layer = texture(uLayerTex, vUv);
            if (uStrokeActive == 1) {
                vec4 stroke;
                if (uStrokeMode == 1) {
                    float coverage = uStrokeIsScreenSpace == 1
                        ? texture(uStrokeCoverageTex, vScreenUv).a
                        : texture(uStrokeCoverageTex, vCanvasUv).a;
                    float alpha = coverage * uStrokeOpacity;
                    stroke = vec4(uStrokeColorLinear * alpha, alpha);
                } else {
                    stroke = (uStrokeIsScreenSpace == 1
                        ? texture(uScreenStrokeTex, vScreenUv)
                        : texture(uStrokeTex, vCanvasUv)) * uStrokeOpacity;
                }
                if (uStrokeErase == 1) layer *= (1.0 - stroke.a);
                else if (uStrokeMode == 2) {
                    layer.rgb = stroke.rgb * layer.rgb + layer.rgb * (1.0 - stroke.a);
                    layer.a = stroke.a + layer.a * (1.0 - stroke.a);
                } else layer = stroke + layer * (1.0 - stroke.a);
            }
            float alpha = layer.a * uOpacity;
            fragColor = vec4(layer.rgb * uOpacity, alpha);
        }
    """
    const val linearPresentFragment = """#version 300 es
        precision highp float;
        in vec2 vUv; uniform sampler2D uTexture; out vec4 fragColor;
        vec3 linearToSrgb(vec3 value){vec3 c=clamp(value,0.0,1.0);vec3 low=c*12.92;vec3 high=1.055*pow(c,vec3(1.0/2.4))-0.055;return mix(low,high,step(vec3(0.0031308),c));}
        vec3 unpremultiply(vec4 value){return value.a<=0.0001?vec3(0.0):value.rgb/value.a;}
        void main(){vec4 color=texture(uTexture,vUv);vec3 srgb=linearToSrgb(unpremultiply(color));fragColor=vec4(srgb*color.a,color.a);}
    """
    // The remaining shader constants are kept in the repository's generated shader source.
}
