package com.wetinknext.engine.gl

object ShaderLib {
    const val dabVertex = """#version 300 es
        layout(location = 0) in vec2 aCorner;
        layout(location = 1) in vec4 iDab0;
        layout(location = 2) in float iAlpha;
        uniform mat4 uCanvasToClip;
        out vec2 vLocal; flat out float vAlpha;
        void main(){ float c=cos(iDab0.w), s=sin(iDab0.w); vec2 r=vec2(c*aCorner.x-s*aCorner.y,s*aCorner.x+c*aCorner.y); vLocal=aCorner;vAlpha=iAlpha;gl_Position=uCanvasToClip*vec4(iDab0.xy+r*iDab0.z,0.,1.); }
    """
    const val dabFragment = """#version 300 es
        precision highp float; in vec2 vLocal; flat in float vAlpha; uniform vec3 uColor; out vec4 fragColor;
        void main(){ float r=length(vLocal), aa=max(fwidth(r),.001), a=vAlpha*(1.-smoothstep(1.-aa,1.,r));fragColor=vec4(uColor*a,a); }
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
