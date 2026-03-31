package com.propaint.app.gl

/**
 * GLSL シェーダーソース (表示専用)。
 * v2 では GPU はキャンバス表示のみ。描画処理は一切なし。
 */
object Shaders {

    /** テクスチャ quad 描画用バーテックスシェーダー */
    const val QUAD_VERT = """
        uniform mat4 uMVP;
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
            vUV = aUV;
        }
    """

    /** テクスチャ描画フラグメントシェーダー */
    const val QUAD_FRAG = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform float uAlpha;
        varying vec2 vUV;
        void main() {
            vec4 c = texture2D(uTex, vUV);
            gl_FragColor = c * uAlpha;
        }
    """

    /** ブラシカーソル円用バーテックスシェーダー（単色ライン） */
    const val CURSOR_VERT = """
        uniform mat4 uMVP;
        attribute vec2 aPos;
        void main() {
            gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
        }
    """

    /** ブラシカーソル円用フラグメントシェーダー（単色） */
    const val CURSOR_FRAG = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """

    /** 選択範囲オーバーレイ (マーチングアンツ) */
    const val SEL_OVERLAY_FRAG = """
        precision mediump float;
        uniform sampler2D uSelMask;
        uniform float uTime;
        uniform vec2 uTexelSize;
        varying vec2 vUV;
        void main() {
            float mask = texture2D(uSelMask, vUV).a;
            float maskL = texture2D(uSelMask, vUV + vec2(-uTexelSize.x, 0.0)).a;
            float maskR = texture2D(uSelMask, vUV + vec2( uTexelSize.x, 0.0)).a;
            float maskU = texture2D(uSelMask, vUV + vec2(0.0, -uTexelSize.y)).a;
            float maskD = texture2D(uSelMask, vUV + vec2(0.0,  uTexelSize.y)).a;
            float edge = abs(mask - maskL) + abs(mask - maskR) + abs(mask - maskU) + abs(mask - maskD);
            if (edge > 0.1) {
                float pos = gl_FragCoord.x + gl_FragCoord.y;
                float pattern = step(0.5, fract((pos - uTime * 40.0) / 8.0));
                gl_FragColor = vec4(pattern, pattern, pattern, 0.85);
            } else if (mask < 0.01) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 0.25);
            } else {
                discard;
            }
        }
    """

    /** チェッカーボード背景 (透明表示用) */
    const val CHECKER_FRAG = """
        precision mediump float;
        varying vec2 vUV;
        uniform vec2 uSize;
        void main() {
            vec2 pos = vUV * uSize;
            float checker = mod(floor(pos.x / 16.0) + floor(pos.y / 16.0), 2.0);
            float gray = mix(0.8, 0.9, checker);
            gl_FragColor = vec4(gray, gray, gray, 1.0);
        }
    """
}
