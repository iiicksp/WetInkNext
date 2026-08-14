package com.wetinknext.engine.gl

object ShaderLib {
    const val compositorVertex="""#version 300 es
        layout(location=0) in vec2 aPosition;uniform mat4 uCanvasToClip;uniform vec2 uCanvasSize;out vec2 vUv;out vec2 vScreenUv;void main(){vUv=aPosition/uCanvasSize;gl_Position=uCanvasToClip*vec4(aPosition,0.,1.);vScreenUv=gl_Position.xy*.5+.5;}"""
    const val compositorFragment = """#version 300 es
        precision highp float;

        in vec2 vUv;
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
                    float coverage = (
                        uStrokeIsScreenSpace == 1
                            ? texture(uStrokeCoverageTex, vScreenUv).a
                            : texture(uStrokeCoverageTex, vUv).a
                    );
                    float alpha = coverage * uStrokeOpacity;
                    stroke = vec4(uStrokeColorLinear * alpha, alpha);
                } else {
                    stroke = (
                        uStrokeIsScreenSpace == 1
                            ? texture(uScreenStrokeTex, vScreenUv)
                            : texture(uStrokeTex, vUv)
                    ) * uStrokeOpacity;
                }

                // Both textures contain premultiplied linear RGBA.
                if (uStrokeErase == 1) {
                    layer *= (1.0 - stroke.a);
                } else if (uStrokeMode == 2) {
                    // Multiply: OpenGL GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA equivalent
                    layer.rgb = stroke.rgb * layer.rgb + layer.rgb * (1.0 - stroke.a);
                    layer.a = stroke.a + layer.a * (1.0 - stroke.a);
                } else {
                    layer = stroke + layer * (1.0 - stroke.a);
                }
            }

            float alpha = layer.a * uOpacity;

            // Layer opacity is applied to premultiplied linear RGB as well.
            fragColor = vec4(layer.rgb * uOpacity, alpha);
        }
    """
    const val linearPresentFragment = """#version 300 es
        precision highp float;

        in vec2 vUv;
        uniform sampler2D uTexture;
        out vec4 fragColor;

        vec3 linearToSrgb(vec3 value) {
            vec3 c = clamp(value, 0.0, 1.0);
            vec3 low = c * 12.92;
            vec3 high = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
            return mix(low, high, step(vec3(0.0031308), c));
        }

        vec3 unpremultiply(vec4 value) {
            return value.a <= 0.0001 ? vec3(0.0) : value.rgb / value.a;
        }

        void main() {
            vec4 color = texture(uTexture, vUv);
            vec3 srgb = linearToSrgb(unpremultiply(color));
            fragColor = vec4(srgb * color.a, color.a);
        }
    """
    const val linearCopyFragment = """#version 300 es
        precision highp float;

        in vec2 vUv;
        uniform sampler2D uTexture;
        out vec4 fragColor;

        void main() {
            fragColor = texture(uTexture, vUv);
        }
    """
    const val dabVertex = """#version 300 es
        layout(location = 0) in vec2 aCorner;
        layout(location = 1) in vec4 iDab0;
        layout(location = 2) in vec3 iDab1;
        layout(location = 3) in vec2 iDab2;
        uniform mat4 uCanvasToClip;
        uniform vec2 uCanvasSize;
        out vec2 vLocal;
        out vec2 vCanvasUv;
        out vec2 vVelocity;
        flat out float vCoverage;
        flat out float vFlow;
        flat out float vHardness;
        void main(){
            float c=cos(iDab0.w), s=sin(iDab0.w);
            vec2 r=vec2(c*aCorner.x-s*aCorner.y,s*aCorner.x+c*aCorner.y);
            vLocal=aCorner;
            vCoverage=iDab1.x;
            vFlow=iDab1.y;
            vHardness=iDab1.z;
            vVelocity=iDab2;
            vec2 p = iDab0.xy+r*iDab0.z;
            vCanvasUv = p / uCanvasSize;
            gl_Position=uCanvasToClip*vec4(p,0.,1.);
        }
    """
    const val dabFragment = """#version 300 es
        precision highp float;
        in vec2 vLocal;
        in vec2 vCanvasUv;
        flat in float vCoverage;
        flat in float vFlow;
        flat in float vHardness;
        uniform vec3 uColorLinear;
        uniform sampler2D uGrainTex;
        uniform int uGrainActive;
        uniform float uGrainScale;
        uniform float uGrainZoomScale;
        uniform int uGrainCanvasLocked;
        uniform int uGrainScreenSpace;
        uniform vec2 uScreenSize;
        uniform float uTextureDepth;
        uniform float uTextureContrast;
        uniform float uStrokeOpacity;
        uniform int uCoverageOnly;
        uniform sampler2D uShapeTex;
        uniform int uShapeActive;
        uniform int uReverseShape;
        uniform int uRgbToAlpha;
        uniform int uFalloffType;
        uniform int uSecondaryShapeActive;
        uniform sampler2D uSecondaryShapeTex;
        uniform float uSecondaryShapeScale;
        uniform sampler2D uSmudgeTex;
        uniform float uSmudgeStrength;
        uniform bool uSquareStroke;
        uniform bool uNoAntialias;
        uniform float uSmudgeLength;
        uniform vec2 uCanvasSize;
        uniform float uEdgeDarkening;
        uniform int uIsWetMode;
        uniform float uWetness;
        in vec2 vVelocity;
        out vec4 fragColor;
        float luminance(vec3 color) {
            return dot(color, vec3(0.299, 0.587, 0.114));
        }
        float applyTextureLevels(float value, float contrast, float depth) {
            float adjusted = clamp((value - 0.5) * contrast + 0.5, 0.0, 1.0);
            return mix(1.0 - depth, 1.0, adjusted);
        }
        float dabCoverage(float r, float hardness) {
            if (uNoAntialias) {
                return r <= 1.0 ? 1.0 : 0.0;
            }
            float aa = max(fwidth(r), .001);
            if (uFalloffType == 0) return 1.0 - smoothstep(1.0 - aa, 1.0 + aa, r);
            if (uFalloffType == 1) {
                float t = clamp(r, 0.0, 1.0);
                float core = 1.0 - t * t * (3.0 - 2.0 * t);
                return core * (1.0 - smoothstep(1.0 - aa, 1.0, r)) * mix(0.4, 1.0, hardness);
            }
            if (uFalloffType == 2) {
                float sigma = mix(0.22, 0.48, 1.0 - hardness);
                return exp(-(r * r) / (2.0 * sigma * sigma)) * (1.0 - smoothstep(1.0 - aa, 1.0, r));
            }
            if (uFalloffType == 3) {
                float t = 1.0 - clamp(r, 0.0, 1.0);
                return t * t * t * (1.0 - smoothstep(1.0 - aa, 1.0, r));
            }
            if (uFalloffType == 4) {
                return smoothstep(1.0, 0.85, r) * (1.0 - smoothstep(1.0 - aa, 1.0 + aa, r));
            }
            float edge = mix(1.0 - aa, 1.0 - aa * .15, clamp(hardness, 0.0, 1.0));
            return 1.0 - smoothstep(edge, 1.0 + aa, r);
        }
        void main(){
            float r = uSquareStroke ? max(abs(vLocal.x), abs(vLocal.y)) : length(vLocal);
            float cov=dabCoverage(r,vHardness);
            if (cov <= 0.0) discard;

            float shapeMask = 1.0;
            if (uShapeActive == 1) {
                vec2 shapeUv = vLocal * 0.5 + 0.5;
                vec4 shapeColor = texture(uShapeTex, shapeUv);
                shapeMask = uRgbToAlpha == 1
                    ? dot(shapeColor.rgb, vec3(0.299, 0.587, 0.114))
                    : shapeColor.a;
                if (uReverseShape == 1) shapeMask = 1.0 - shapeMask;
            }
            if (uSecondaryShapeActive == 1) {
                vec2 secUv = (vLocal * 0.5 + 0.5) * max(uSecondaryShapeScale, 0.0001);
                secUv = secUv - (max(uSecondaryShapeScale, 0.0001) * 0.5) + 0.5;
                vec4 secColor = texture(uSecondaryShapeTex, secUv);
                float secMask = uRgbToAlpha == 1
                    ? dot(secColor.rgb, vec3(0.299, 0.587, 0.114))
                    : secColor.a;
                if (uReverseShape == 1) secMask = 1.0 - secMask;
                shapeMask *= secMask;
            }

            float grainFactor = 1.0;
            if (uGrainActive == 1) {
                vec2 uv;
                if (uGrainScreenSpace == 1) {
                    uv = (gl_FragCoord.xy / uScreenSize.y) * max(uGrainScale, 0.0001);
                } else {
                    uv = uGrainCanvasLocked == 1
                        ? vCanvasUv * max(uGrainScale * uGrainZoomScale, 0.0001)
                        : (vLocal * 0.5 + 0.5) * max(uGrainScale * uGrainZoomScale, 0.0001);
                }
                vec4 grainColor = texture(uGrainTex, uv);
                float effectiveDepth = uTextureDepth * mix(1.0, 0.2, vCoverage);
                grainFactor = applyTextureLevels(
                    luminance(grainColor.rgb), uTextureContrast, effectiveDepth
                );
            }

            float coverage = vCoverage * vFlow * cov * shapeMask * grainFactor;
            if (uCoverageOnly == 1) {
                fragColor = vec4(0.0, 0.0, 0.0, coverage);
                return;
            }
            float a = coverage * uStrokeOpacity;
            vec3 color = uColorLinear;
            if (uSmudgeStrength > 0.0) {
                float vlen = length(vVelocity);
                if (vlen > 0.001) {
                    vec2 pull = (vVelocity / vlen) * uSmudgeLength * 100.0;
                    vec2 smudgeUv = vCanvasUv - (pull / uCanvasSize);
                    vec3 smudgeColor = texture(uSmudgeTex, smudgeUv).rgb;
                    color = mix(color, smudgeColor, uSmudgeStrength);
                }
            }
            if (uEdgeDarkening > 0.0) {
                float edge = 1.0 - abs((coverage - 0.5) * 2.0);
                edge = smoothstep(0.0, 1.0, edge);
                color = mix(color, color * 0.2, edge * uEdgeDarkening);
            }
            // Additive fluid-buffer deposit for a WET brush.
            // RGB = premultiplied pigment, A = WATER (drives diffusion / carry).
            // Water is weighed by the brush wetness so the wash spreads and
            // dries the way the brush is set, not by pigment coverage.
            if (uIsWetMode == 1) {
                fragColor = vec4(color * coverage, coverage * clamp(uWetness, 0.0, 1.0));
            } else {
                fragColor = vec4(color * a, a);
            }
        }
    """
    const val nonBuildupStrokeFragment = """#version 300 es
        #extension GL_EXT_shader_framebuffer_fetch : enable
        precision highp float;

        in vec2 vUv;
        uniform sampler2D uCoverageTex;
        uniform vec3 uColorLinear;
        uniform float uOpacity;
        uniform float uEdgeDarkening;
        uniform int uStrokeMode;
        
        #ifdef GL_EXT_shader_framebuffer_fetch
        layout(location = 0) inout vec4 fragColor;
        #else
        out vec4 fragColor;
        #endif

        void main() {
            float coverage = texture(uCoverageTex, vUv).a;
            float alpha = coverage * uOpacity;
            vec3 color = uColorLinear;
            if (uEdgeDarkening > 0.0) {
                float edge = 1.0 - abs((coverage - 0.5) * 2.0);
                edge = smoothstep(0.0, 1.0, edge);
                color = mix(color, color * 0.2, edge * uEdgeDarkening);
            }
            
            vec4 src = vec4(color * alpha, alpha);
            
            #ifdef GL_EXT_shader_framebuffer_fetch
            if (uStrokeMode == 2) {
                vec4 dst = fragColor;
                fragColor = vec4(src.rgb * dst.rgb + src.rgb * (1.0 - dst.a) + dst.rgb * (1.0 - src.a), src.a + dst.a * (1.0 - src.a));
                return;
            }
            #endif
            
            fragColor = src;
        }
    """
    const val fullscreenVertex = """#version 300 es
        layout(location = 0) in vec2 aPosition;
        out vec2 vUv;
        void main() { vUv = aPosition * 0.5 + 0.5; gl_Position = vec4(aPosition, 0.0, 1.0); }
    """
    const val canvasBackdropFragment = """#version 300 es
        precision highp float;

        uniform vec3 uBackgroundColor;
        uniform vec3 uGridColor;
        uniform int uMode;
        out vec4 fragColor;

        void main() {
            const float cellPx = 28.0;
            vec2 pixel = gl_FragCoord.xy;
            vec3 color = uBackgroundColor;

            if (uMode == 1) {
                vec2 insideCell = mod(pixel, cellPx);
                bool isGridLine = insideCell.x < 1.0 || insideCell.y < 1.0;
                color = isGridLine ? uGridColor : uBackgroundColor;
            } else {
                vec2 cell = floor(pixel / cellPx);
                float parity = mod(cell.x + cell.y, 2.0);
                color = parity < 1.0 ? uGridColor : uBackgroundColor;
            }

            fragColor = vec4(color, 1.0);
        }
    """

    /**
     * Один сегмент штриха = капсула (round cone): два круга с линейной интерполяцией радиуса.
     * Вершинный шейдер строит только AABB сегмента, форма считается во фрагменте.
     */
    const val capsuleVertex = """#version 300 es
        layout(location = 0) in vec2 aCorner;   // -1..1, TRIANGLE_STRIP из 4 вершин
        layout(location = 1) in vec4 iA;        // x, y, radius, coverage (начало сегмента)
        layout(location = 2) in vec4 iB;        // x, y, radius, coverage (конец сегмента)
        uniform mat4 uCanvasToClip;
        uniform vec2 uCanvasSize;
        out vec2 vPos;
        out vec2 vCanvasUv;
        flat out vec4 vA;
        flat out vec4 vB;
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
     * Рисовать premultiplied source-over blending:
     * GL_FUNC_ADD + GL_ONE, GL_ONE_MINUS_SRC_ALPHA.
     */
    const val capsuleFragment = """#version 300 es
        precision highp float;
        in vec2 vPos;
        in vec2 vCanvasUv;
        flat in vec4 vA;
        flat in vec4 vB;
        uniform vec3 uColorLinear;
        uniform sampler2D uGrainTex;
        uniform int uGrainActive;
        uniform float uGrainScale;
        uniform float uGrainZoomScale;
        uniform int uGrainCanvasLocked;
        uniform float uTextureDepth;
        uniform float uTextureContrast;
        uniform float uFlow;
        uniform int uCoverageOnly;
        uniform float uEdgeDarkening;
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

            float grainFactor = 1.0;
            if (uGrainActive == 1) {
                vec2 uv = vCanvasUv * max(uGrainScale * uGrainZoomScale, 0.0001);
                float val = texture(uGrainTex, uv).r;
                val = clamp((val - 0.5) * uTextureContrast + 0.5, 0.0, 1.0);
                grainFactor = mix(1.0 - uTextureDepth, 1.0, val);
            }

            vec2 ab = vB.xy - vA.xy;
            float abLengthSquared = max(dot(ab, ab), 0.000001);
            float segmentT = clamp(
                dot(vPos - vA.xy, ab) / abLengthSquared,
                0.0,
                1.0
            );
            float segmentCoverage = mix(vA.w, vB.w, segmentT);
            float coverage = cov * segmentCoverage * grainFactor * uFlow;
            if (uCoverageOnly == 1) {
                fragColor = vec4(0.0, 0.0, 0.0, coverage);
            } else {
                vec3 color = uColorLinear;
                if (uEdgeDarkening > 0.0) {
                    float edge = 1.0 - abs((coverage - 0.5) * 2.0);
                    edge = smoothstep(0.0, 1.0, edge);
                    color = mix(color, color * 0.2, edge * uEdgeDarkening);
                }
                fragColor = vec4(color * coverage, coverage);
            }
        }
    """

    const val strokeBlitFragment = """#version 300 es
        #extension GL_EXT_shader_framebuffer_fetch : enable
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uStrokeTex;
        uniform float uOpacity;
        uniform int uStrokeMode;
        
        #ifdef GL_EXT_shader_framebuffer_fetch
        layout(location = 0) inout vec4 fragColor;
        #else
        out vec4 fragColor;
        #endif
        
        void main() { 
            vec4 src = texture(uStrokeTex, vUv) * uOpacity;
            #ifdef GL_EXT_shader_framebuffer_fetch
            if (uStrokeMode == 2) {
                vec4 dst = fragColor;
                fragColor = vec4(src.rgb * dst.rgb + src.rgb * (1.0 - dst.a) + dst.rgb * (1.0 - src.a), src.a + dst.a * (1.0 - src.a));
                return;
            }
            #endif
            fragColor = src;
        }
    """

    const val ribbonMeshVertex = """#version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in float aCoverage;
        uniform mat4 uCanvasToClip;
        uniform vec2 uCanvasSize;
        out vec2 vCanvasUv;
        out float vCoverage;
        void main() {
            vCoverage = aCoverage;
            vCanvasUv = aPosition / uCanvasSize;
            gl_Position = uCanvasToClip * vec4(aPosition, 0.0, 1.0);
        }
    """
    const val ribbonMeshFragment = """#version 300 es
        precision highp float;
        in float vCoverage;
        in vec2 vCanvasUv;
        
        uniform vec3 uColorLinear;
        uniform float uFlow;
        uniform int uCoverageOnly;
        uniform float uEdgeDarkening;
        
        uniform int uGrainActive;
        uniform sampler2D uGrainTex;
        uniform float uGrainScale;
        uniform float uGrainZoomScale;
        uniform int uGrainScreenSpace;
        uniform vec2 uScreenSize;
        uniform float uTextureContrast;
        uniform float uTextureDepth;
        
        out vec4 fragColor;
        
        float luminance(vec3 color) {
            return dot(color, vec3(0.299, 0.587, 0.114));
        }
        float applyTextureLevels(float value, float contrast, float depth) {
            float adjusted = clamp((value - 0.5) * contrast + 0.5, 0.0, 1.0);
            return mix(1.0 - depth, 1.0, adjusted);
        }
        
        void main() {
          float grainFactor = 1.0;
          if (uGrainActive == 1) {
              vec2 uv = uGrainScreenSpace == 1 
                  ? (gl_FragCoord.xy / uScreenSize.y) * max(uGrainScale, 0.0001)
                  : vCanvasUv * max(uGrainScale * uGrainZoomScale, 0.0001);
              vec4 grainColor = texture(uGrainTex, uv);
              float effectiveDepth = uTextureDepth * mix(1.0, 0.2, vCoverage);
              grainFactor = applyTextureLevels(
                  luminance(grainColor.rgb), uTextureContrast, effectiveDepth
              );
          }
          
          float coverage = clamp(vCoverage * uFlow * grainFactor, 0.0, 1.0);
          if (uCoverageOnly == 1) {
            fragColor = vec4(0.0, 0.0, 0.0, coverage);
          } else {
            vec3 color = uColorLinear;
            if (uEdgeDarkening > 0.0) {
                float edge = 1.0 - abs((coverage - 0.5) * 2.0);
                edge = smoothstep(0.0, 1.0, edge);
                color = mix(color, color * 0.2, edge * uEdgeDarkening);
            }
            fragColor = vec4(color * coverage, coverage);
          }
        }
    """

    const val wetSimFragment = """#version 300 es
        precision highp float;
        in vec2 vUv;
        out vec4 fragColor;

        uniform sampler2D uPigmentTex;
        uniform vec2 uPixelSize;
        uniform vec2 uMotion;          // brush-tip velocity in document UV/sec
        uniform float uDeltaTime;      // real seconds since the previous step
        uniform float uSpread;         // how far a wash bleeds
        uniform float uWetness;        // global water loading of the brush
        uniform float uBleed;          // fraction of water movement carrying pigment
        uniform float uAdvection;      // how strongly the live tip pushes wet paint
        uniform float uCoagulation;    // pigment clumping at the wet boundary
        uniform float uEvaporation;    // water lost per second
        uniform float uEdgeDarkening;  // extra darkening at the wash edge (finalize)
        uniform int uFinalize;         // 1 = output pigment coverage for the commit

        void main(){
            vec2 px = uPixelSize;

            // ---- advect: the live brush tip pushes existing wet paint backwards ----
            vec2 shift = clamp(uMotion, vec2(-8.0), vec2(8.0)) * uDeltaTime * clamp(uAdvection, 0.0, 1.0);
            vec4 wet = texture(uPigmentTex, vUv - shift);

            // ---- 8-neighbour gather for near-isotropic diffusion ----
            vec4 e  = texture(uPigmentTex, vUv + vec2( px.x,  0.0));
            vec4 w  = texture(uPigmentTex, vUv + vec2(-px.x,  0.0));
            vec4 n  = texture(uPigmentTex, vUv + vec2( 0.0,  px.y));
            vec4 s  = texture(uPigmentTex, vUv + vec2( 0.0, -px.y));
            vec4 ne = texture(uPigmentTex, vUv + vec2( px.x,  px.y));
            vec4 nw = texture(uPigmentTex, vUv + vec2(-px.x,  px.y));
            vec4 se = texture(uPigmentTex, vUv + vec2( px.x, -px.y));
            vec4 sw = texture(uPigmentTex, vUv + vec2(-px.x, -px.y));

            float wMean = (w.a + e.a + n.a + s.a + 0.5 * (nw.a + ne.a + sw.a + se.a)) / 6.0;
            vec3  pMean = (w.rgb + e.rgb + n.rgb + s.rgb + 0.5 * (nw.rgb + ne.rgb + sw.rgb + se.rgb)) / 6.0;

            // Wetter regions bleed further; a wet brush loads the whole wash.
            float waterRate = clamp(uSpread * (0.35 + 0.65 * wet.a) * (0.5 + 0.5 * clamp(uWetness, 0.0, 1.0)), 0.0, 1.0);

            // Water spreads at waterRate; pigment is carried by a fraction of that
            // movement (bleed), though it still diffuses a little on its own so
            // the wash merges instead of merely sliding.
            float water = mix(wet.a, wMean, waterRate);
            vec3  pig   = mix(wet.rgb, pMean, waterRate * mix(0.25, 1.0, clamp(uBleed, 0.0, 1.0)));

            // ---- coagulation: pigment piles up where water thins (dark wet rim) ----
            float gx = e.a - w.a;
            float gy = n.a - s.a;
            float grad = clamp(length(vec2(gx, gy)), 0.0, 1.0);
            float rim  = clamp(uCoagulation, 0.0, 1.0) * grad;
            pig = mix(pig, pig * (1.0 + rim * 0.6), rim);

            // ---- evaporation: water dries over real time; thin washes settle ----
            water = max(water - clamp(uEvaporation, 0.0, 1.0) * uDeltaTime, 0.0);

            float pigCoverage = clamp(max(max(pig.r, pig.g), pig.b), 0.0, 1.0);

            if (uFinalize == 1) {
                // Commit pass: alpha becomes pigment coverage so a drying wash is
                // not faded by leftover water. rgb stays premultiplied pigment.
                vec3 color = clamp(pig, 0.0, 1.0);
                float dark = clamp(uEdgeDarkening, 0.0, 1.0);
                float edge = 1.0 - abs((pigCoverage - 0.5) * 2.0);
                edge = smoothstep(0.0, 1.0, edge) * dark;
                color = mix(color, color * 0.22, edge);
                fragColor = vec4(color, pigCoverage);
            } else {
                fragColor = vec4(clamp(pig, 0.0, 1.0), water);
            }
        }
    """

    const val wetCompositeFragment = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uFluidTex;
        out vec4 fragColor;
        
        void main() {
            vec4 fluid = texture(uFluidTex, vUv);
            // The fluid buffer stores (R,G,B) as premultiplied pigment.
            // Wetness is in alpha, but for rendering we want pigment coverage.
            // If the brush was completely dry, we still see pigment.
            // We can derive alpha from the max component or a separate pigment density channel if we had one.
            // Since we use RGB for premultiplied pigment, the alpha is max(r,g,b)
            float pigmentAlpha = clamp(max(max(fluid.r, fluid.g), fluid.b), 0.0, 1.0);
            
            // Output standard pre-multiplied RGBA
            fragColor = vec4(fluid.rgb, pigmentAlpha);
        }
    """
}
