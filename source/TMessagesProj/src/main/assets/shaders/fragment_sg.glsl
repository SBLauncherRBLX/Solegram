precision highp float;

// Solegram's 3D logo on the intro screen: matte, a soft clear coat, the colours of the flat logo.
// vUV.x = the part (0.1 sun, 0.5 upper stripe, 0.9 lower stripe), vUV.y = 0 at the part's top, 1 at its bottom.
uniform float f_alpha;
uniform mat4 world;
uniform float white;
uniform float time;

varying vec3 vNormal;
varying vec2 vUV;
varying vec3 modelViewVertex;

vec3 partColor(float part, float t) {
    if (part < 0.3) return mix(RGB#FFB52E, RGB#FF6A2F, t);
    if (part < 0.7) return mix(RGB#FF7A3A, RGB#F2512C, t);
    return mix(RGB#F45A30, RGB#DC3A22, t);
}

void main() {
    vec3 n = normalize(vec3(world * vec4(vNormal, 0.0)));
    vec3 v = vec3(0.0, 0.0, 1.0);
    vec3 base = partColor(vUV.x, clamp(vUV.y, 0.0, 1.0));

    vec3 key = normalize(vec3(-30.0, 50.0, 60.0));
    vec3 rim = normalize(vec3(40.0, -20.0, -40.0));
    vec3 fill = normalize(vec3(30.0, -10.0, 60.0));

    float dKey = max(dot(n, key), 0.0);
    float dFill = max(dot(n, fill), 0.0);
    float dRim = max(dot(n, rim), 0.0);
    vec3 color = base * (0.42 + 0.62 * dKey + 0.18 * dFill);
    color += RGB#FF8A5C * 0.35 * dRim;

    // a soft clear coat: wide highlight from the key light, a thin moving glint
    vec3 h = normalize(key + v);
    float spec = pow(max(dot(n, h), 0.0), 28.0) * 0.22;
    vec3 h2 = normalize(normalize(vec3(sin(time * 0.35) * 40.0, 30.0, 60.0)) + v);
    spec += pow(max(dot(n, h2), 0.0), 90.0) * 0.12;
    color += vec3(1.0, 0.95, 0.88) * spec;

    // the edges light up a little, as on the preview
    float fresnel = pow(1.0 - max(dot(n, v), 0.0), 3.0);
    color += RGB#FFC27A * fresnel * 0.18;

    color = min(color, vec3(1.0));
    gl_FragColor = mix(vec4(color, 1.0) * f_alpha, vec4(1.0), white);
}
