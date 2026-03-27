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
