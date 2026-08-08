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
        uniform float uStrokeOpacity;

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
                vec4 stroke = texture(uStrokeTex, vUv) * uStrokeOpacity;
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
    const val ribbonVertex = """#version 300 es
        layout(location=0) in vec2 aPos; layout(location=1) in float aCoverage; layout(location=2) in float aAlpha;
        uniform mat4 uCanvasToClip; out float vCoverage; out float vAlpha;
        void main(){vCoverage=aCoverage;vAlpha=aAlpha;gl_Position=uCanvasToClip*vec4(aPos,0.,1.);}
    """
    const val ribbonFragment = """#version 300 es
        precision highp float; in float vCoverage; in float vAlpha; uniform vec3 uColorLinear; uniform float uFlow;
        uniform int uAntiAliasLevel; uniform int uNoAntialias; out vec4 fragColor;
        void main(){float cov=vCoverage;if(uNoAntialias==1||uAntiAliasLevel==0)cov=step(.5,cov);else{float e=uAntiAliasLevel==1?.35:uAntiAliasLevel==2?.5:.7;cov=smoothstep(.5-e,.5+e,cov);}float a=vAlpha*cov*uFlow;fragColor=vec4(uColorLinear*a,a);}
    """

    /**
     * Один сегмент штриха = капсула (round cone): два круга с линейной интерполяцией радиуса.
     * Вершинный шейдер строит только AABB сегмента, форма считается во фрагменте.
     */
    const val capsuleVertex = """#version 300 es
        layout(location = 0) in vec2 aCorner;   // -1..1, TRIANGLE_STRIP из 4 вершин
        layout(location = 1) in vec3 iA;        // x, y, radius (начало сегмента)
        layout(location = 2) in vec3 iB;        // x, y, radius (конец сегмента)
        uniform mat4 uCanvasToClip;
        uniform vec2 uCanvasSize;
        out vec2 vPos;
        out vec2 vCanvasUv;
        flat out vec3 vA;
        flat out vec3 vB;
        void main() {
            // Запас 2 px на AA-переход, иначе край обрежется по границе квада.
            vec2 lo = min(iA.xy - iA.z, iB.xy - iB.z) - 2.0;
            vec2 hi = max(iA.xy + iA.z, iB.xy + iB.z) + 2.0;
            vec2 p = mix(lo, hi, aCorner * 0.5 + 0.5);
            vPos = p;
            vCanvasUv = p / uCanvasSize;
            vA = iA;
            vB = iB;
            gl_Position = uCanvasToClip * vec4(p, 0.0, 1.0);
        }
    """

    /**
     * Аналитическое покрытие: нет каппов, джойнов, miter и AA-юбки как отдельных сущностей.
     * Выход строго premultiplied linear: rgb = color * a, a = cov.
     * Рисовать ТОЛЬКО с glBlendEquation(GL_MAX) + glBlendFunc(GL_ONE, GL_ONE):
     * MAX идемпотентен, поэтому перекрытия сегментов не накапливают альфу.
     */
    const val capsuleFragment = """#version 300 es
        precision highp float;
        in vec2 vPos;
        in vec2 vCanvasUv;
        flat in vec3 vA;
        flat in vec3 vB;
        uniform vec3 uColorLinear;
        uniform sampler2D uGrainTex;
        uniform int uGrainActive;
        uniform float uGrainScale;
        uniform int uGrainCanvasLocked;
        out vec4 fragColor;

        float sdRoundCone(vec2 p, vec2 a, vec2 b, float r1, float r2) {
            vec2 ba = b - a;
            float l2 = dot(ba, ba);
            float rr = r1 - r2;

            // Guard 1: вырожденный сегмент (тап, дубль сэмпла) -> обычный круг.
            if (l2 < 1e-6) return length(p - a) - max(r1, r2);
            // Guard 2: один круг поглощает другой (скачок давления на коротком шаге).
            // Без этого a2 < 0 -> sqrt(отрицательного) -> NaN -> чёрные клинья.
            if (rr * rr >= l2) return (r1 > r2) ? (length(p - a) - r1) : (length(p - b) - r2);

            float a2 = l2 - rr * rr;
            float il2 = 1.0 / l2;
            vec2 pa = p - a;
            float y = dot(pa, ba);
            float z = y - l2;
            vec2 xv = pa * l2 - ba * y;
            float x2 = dot(xv, xv);
            float y2 = y * y * l2;
            float z2 = z * z * l2;
            float k = sign(rr) * rr * rr * x2;

            if (sign(z) * a2 * z2 > k) return sqrt(x2 + z2) * il2 - r2;   // круглый конец у B
            if (sign(y) * a2 * y2 < k) return sqrt(x2 + y2) * il2 - r1;   // круглый конец у A
            return (sqrt(x2 * a2 * il2) + y * rr) * il2 - r1;             // касательная боковина
        }

        void main() {
            float d = sdRoundCone(vPos, vA.xy, vB.xy, vA.z, vB.z);
            float w = max(fwidth(d), 1e-4);
            float cov = 1.0 - smoothstep(-w, w, d);
            if (cov <= 0.0) discard;

            float grain = 1.0;
            if (uGrainActive == 1) {
                vec2 uv = vCanvasUv * max(uGrainScale, 0.0001);
                grain = texture(uGrainTex, uv).r;
            }

            float finalAlpha = cov * grain;
            fragColor = vec4(uColorLinear * finalAlpha, finalAlpha);
        }
    """

    const val strokeBlitFragment = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uStrokeTex;
        uniform float uOpacity;
        out vec4 fragColor;
        void main() { fragColor = texture(uStrokeTex, vUv) * uOpacity; }
    """
}
