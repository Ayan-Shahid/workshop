package pk.workshop.ui

/**
 * Skia (SkSL) shaders for the app's surfaces. All are static: they are drawn once per size and theme and cached,
 * so they cost nothing while scrolling or typing, even on a PC without a graphics card.
 *
 * Colour uniforms are straight RGBA in 0..1.
 */
object Shaders {
    /** Value noise and fractal noise shared by the shaders. */
    private const val NOISE = """
float hash(float2 p) { p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * noise(p); p = p * 2.03 + float2(17.0, 9.0); a *= 0.5; }
    return v;
}
float fbm3(float2 p) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < 3; i++) { v += a * noise(p); p = p * 2.03 + float2(17.0, 9.0); a *= 0.5; }
    return v;
}
"""

    /**
     * Background A, "Topographic": thin contour lines like a terrain map or an engineering drawing,
     * every fourth line a little stronger, with a soft glow of the primary colour in the top-right corner.
     */
    const val BG_TOPO = NOISE + """
uniform float2 size;
uniform half4 base;
uniform half4 ink;
uniform float lineAlpha;
uniform float glowAlpha;
half4 main(float2 fc) {
    float scale = 560.0;
    float2 o = float2(3.1, 7.7);
    float h  = fbm(fc / scale + o);
    float hx = fbm((fc + float2(1.0, 0.0)) / scale + o);
    float hy = fbm((fc + float2(0.0, 1.0)) / scale + o);
    float levels = 18.0;
    float g = length(float2(hx - h, hy - h)) * levels;       // contour spacing change per pixel
    float b = h * levels;
    float d = abs(fract(b + 0.5) - 0.5) / max(g, 0.0001);   // distance to nearest contour, in pixels
    float line = 1.0 - smoothstep(0.35, 1.25, d);
    float major = (mod(floor(b + 0.5), 4.0) == 0.0) ? 1.0 : 0.5;
    float2 gc = (fc - float2(size.x * 1.02, -size.y * 0.15)) / max(size.x, size.y);
    float glow = (1.0 - smoothstep(0.0, 0.95, length(gc))) * glowAlpha;
    half3 c = mix(base.rgb, ink.rgb, glow);
    c = mix(c, ink.rgb, line * major * lineAlpha);
    c += (hash(fc) - 0.5) * 0.006;                            // a touch of grain so gradients don't band
    return half4(c, 1.0);
}
"""

    /**
     * Background B, "Silk": slow, domain-warped folds of the primary colour, strongest along the top
     * and fading to the plain background lower down, like light on draped fabric.
     */
    const val BG_SILK = NOISE + """
uniform float2 size;
uniform half4 base;
uniform half4 ink;
uniform half4 ink2;
uniform float amount;
half4 main(float2 fc) {
    float2 p = fc / max(size.x, size.y) * 2.4;
    float2 q = float2(fbm(p), fbm(p + float2(5.2, 1.3)));
    float2 r = float2(fbm(p + 3.0 * q + float2(1.7, 9.2)), fbm(p + 3.0 * q + float2(8.3, 2.8)));
    float f = fbm(p + 2.5 * r);
    float top = 1.0 - smoothstep(0.0, 1.0, fc.y / size.y);
    float lit = smoothstep(0.42, 0.78, f) * (0.25 + 0.75 * top) * amount;
    float deep = (1.0 - smoothstep(0.25, 0.5, f)) * (0.3 + 0.7 * top) * amount * 0.55;
    half3 c = mix(base.rgb, ink.rgb, lit);
    c = mix(c, ink2.rgb, deep);
    c += (hash(fc) - 0.5) * 0.008;
    return half4(c, 1.0);
}
"""

    /**
     * Card A, "Brushed steel": fine horizontal streaks like brushed aluminium, with a soft diagonal sheen.
     * Very low contrast so text on top stays crisp.
     */
    const val CARD_BRUSHED = NOISE + """
uniform float2 size;
uniform half4 base;
uniform half4 sheen;
uniform float streak;
uniform float sheenAmt;
half4 main(float2 fc) {
    float s = noise(float2(fc.x / 240.0, fc.y / 1.4)) * 0.6 + noise(float2(fc.x / 70.0 + 40.0, fc.y / 0.8 + 50.0)) * 0.4;
    float2 uv = fc / size;
    float band = uv.x * 0.8 - uv.y * 0.55 - 0.15;
    float sh = exp(-band * band * 9.0);
    half3 c = base.rgb + (s - 0.5) * streak;
    c = mix(c, sheen.rgb, sh * sheenAmt);
    return half4(c, 1.0);
}
"""

    /**
     * Card B, "Matte paper": an even, fine grain with light falling from the top-left,
     * like good-quality matte card stock.
     */
    const val CARD_MATTE = NOISE + """
uniform float2 size;
uniform half4 base;
uniform half4 sheen;
uniform float grain;
uniform float sheenAmt;
half4 main(float2 fc) {
    float g = hash(floor(fc)) - 0.5;
    float g2 = noise(fc / 3.0) - 0.5;
    float2 uv = fc / size;
    float light = clamp(1.0 - (uv.y * 0.75 + uv.x * 0.25), 0.0, 1.0);
    half3 c = base.rgb + (g * 0.6 + g2 * 0.4) * grain;
    c = mix(c, sheen.rgb, light * sheenAmt);
    return half4(c, 1.0);
}
"""

    /**
     * Primary buttons: a mesh gradient of five shades of the primary colour (no other hues), blended with
     * inverse-distance weights and a gentle warp so it reads as a soft mesh rather than a straight gradient.
     */
    const val BUTTON_MESH = """
uniform float2 size;
uniform half4 c0;   // lightest shade
uniform half4 c1;   // primary
uniform half4 c2;   // deeper
uniform half4 c3;   // deepest
uniform half4 c4;   // primary, a little brighter
float w(float2 a, float2 b) { float2 d = (a - b) * float2(1.0, 0.55); return 1.0 / (dot(d, d) + 0.015); }
half4 main(float2 fc) {
    float2 uv = fc / size;
    uv += 0.07 * float2(sin(uv.y * 5.0 + 1.3), sin(uv.x * 6.0 + 2.1));
    // Shades are scattered (light bottom-left, deepest top-right) so the blend never reads as light from above, i.e. no 3D bevel look
    float w0 = w(uv, float2(0.05, 1.0)); float w1 = w(uv, float2(0.0, 0.0)); float w2 = w(uv, float2(1.0, 1.0));
    float w3 = w(uv, float2(1.0, 0.0)); float w4 = w(uv, float2(0.5, 0.45)) * 0.8;
    half3 c = (c0.rgb * w0 + c1.rgb * w1 + c2.rgb * w2 + c3.rgb * w3 + c4.rgb * w4) / (w0 + w1 + w2 + w3 + w4);
    return half4(c, 1.0);
}
"""

    /**
     * Background C, "Forged carbon": chopped carbon flakes set in resin. Irregular flakes, each with its own fibre
     * direction, catch the light differently (anisotropic sheen); thin dark resin seams run between them.
     */
    const val BG_FORGED = NOISE + """
uniform float2 size;
uniform half4 resin;
uniform half4 flakeLo;
uniform half4 flakeHi;
uniform half4 ink;
uniform float glowAlpha;
uniform float scale;
half4 main(float2 fc) {
    float2 q = fc / scale;
    q += 1.1 * float2(fbm3(q * 0.3 + 3.0), fbm3(q * 0.3 + 11.0)) - 0.45;     // swirl, as chips flow in the mould
    float best = -1.0; float2 bid = float2(0.0); float bang = 0.0; float bedge = 1.0;
    // Three layers of elongated chips at different angles; at each point the "top" chip wins, so chips overlap
    for (int L = 0; L < 3; L++) {
        float la = float(L) * 2.1 + 0.4;
        float2 r0 = float2(cos(la) * q.x - sin(la) * q.y, sin(la) * q.x + cos(la) * q.y);
        float2 w = r0 * float2(0.42, 1.3) + float(L) * 7.3;
        float2 cl = floor(w); float2 f = fract(w);
        float d1 = 9.0; float d2 = 9.0; float2 id = float2(0.0);
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                float2 g = float2(float(x), float(y));
                float2 o = float2(hash(cl + g), hash(cl + g + float2(19.19, 7.31)));
                float2 r = g + o - f;
                float d = dot(r, r);
                if (d < d1) { d2 = d1; d1 = d; id = cl + g; } else if (d < d2) { d2 = d; }
            }
        }
        float h = hash(id + float(L) * 13.1);
        if (h > best) { best = h; bid = id + float(L) * 31.7; bang = la + (hash(id + 2.2) - 0.5) * 0.7; bedge = sqrt(d2) - sqrt(d1); }
    }
    float2 dir = float2(cos(bang), sin(bang));
    float fib = noise(float2(dot(fc, dir) / 45.0, dot(fc, float2(-dir.y, dir.x)) / 1.2));
    float aniso = pow(abs(cos(bang - 0.7)), 4.0);               // chips whose fibres face the light shine
    float bright = hash(bid + 8.1);
    float v = clamp((0.25 * bright + 0.75 * aniso) * (0.65 + 0.7 * fib), 0.0, 1.0);
    v = pow(v, 1.4);
    half3 c = mix(flakeLo.rgb, flakeHi.rgb, v);
    c = mix(c, resin.rgb, (1.0 - smoothstep(0.0, 0.05, bedge)) * 0.5);
    float2 gc = (fc - float2(size.x * 1.02, -size.y * 0.15)) / max(size.x, size.y);
    c = mix(c, ink.rgb, (1.0 - smoothstep(0.0, 0.95, length(gc))) * glowAlpha);
    c += (hash(fc) - 0.5) * 0.006;
    return half4(c, 1.0);
}
"""

    /**
     * Card C, "Carbon fibre": classic 2x2 twill weave. Each tow is shaded round across its width and dips at its ends;
     * horizontal and vertical tows reflect differently, which gives carbon its diagonal checker shimmer.
     */
    const val CARD_CARBON = NOISE + """
uniform float2 size;
uniform half4 base;
uniform half4 lo;
uniform half4 hi;
uniform float tow;
uniform float amount;
half4 main(float2 fc) {
    float2 p = fc / tow;
    float2 cl = floor(p); float2 f = fract(p);
    float k = mod(cl.x - cl.y, 4.0);
    bool horiz = k < 2.0;
    float across = horiz ? f.y : f.x;
    float t = horiz ? (k + f.x) / 2.0 : ((3.0 - k) + f.y) / 2.0;
    float bulge = sin(3.14159 * across);
    float ends = smoothstep(0.0, 0.14, t) * (1.0 - smoothstep(0.86, 1.0, t));
    float fib = noise(horiz ? float2(fc.x / 26.0, fc.y * 1.2) : float2(fc.x * 1.2, fc.y / 26.0));
    float v = (horiz ? 1.0 : 0.5) * bulge * (0.35 + 0.65 * ends) * (0.8 + 0.4 * fib);
    half3 c = mix(lo.rgb, hi.rgb, clamp(v, 0.0, 1.0));
    return half4(mix(base.rgb, c, amount), 1.0);
}
"""

    /**
     * Seamless woven-carbon tile (2x2 twill). Everything repeats exactly every [tile] pixels, so the image can be
     * repeated across any surface without seams. tile must be a multiple of 4 × tow and of streak.
     */
    const val CARD_CARBON_TILE = """
uniform half4 lo;
uniform half4 hi;
uniform half4 base;
uniform float tow;
uniform float streak;
uniform float tile;
uniform float amount;
float hashP(float2 p, float2 per) { p = mod(p, per); p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noiseP(float2 p, float2 per) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hashP(i, per), hashP(i + float2(1.0, 0.0), per), u.x), mix(hashP(i + float2(0.0, 1.0), per), hashP(i + float2(1.0, 1.0), per), u.x), u.y);
}
half4 main(float2 fc) {
    float2 p = fc / tow;
    float2 cl = floor(p); float2 f = fract(p);
    float k = mod(cl.x - cl.y, 4.0);
    bool horiz = k < 2.0;
    float across = horiz ? f.y : f.x;
    float t = horiz ? (k + f.x) / 2.0 : ((3.0 - k) + f.y) / 2.0;
    float bulge = sin(3.14159 * across);
    float ends = smoothstep(0.0, 0.14, t) * (1.0 - smoothstep(0.86, 1.0, t));
    float fib = horiz ? noiseP(float2(fc.x / streak, fc.y), float2(tile / streak, tile))
                      : noiseP(float2(fc.x, fc.y / streak), float2(tile, tile / streak));
    float v = (horiz ? 1.0 : 0.5) * bulge * (0.35 + 0.65 * ends) * (0.8 + 0.4 * fib);
    half3 c = mix(lo.rgb, hi.rgb, clamp(v, 0.0, 1.0));
    return half4(mix(base.rgb, c, amount), 1.0);
}
"""
}
