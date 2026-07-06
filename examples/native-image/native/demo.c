typedef struct { float x; float y; } point;

point make_point(float x, float y) {
    point p;
    p.x = x;
    p.y = y;
    return p;
}

float point_sum(point p) { return p.x + p.y; }

void write_int(int* out, int v) { *out = v; }

int apply_cb(int (*f)(int), int v) { return f(v) * 2; }
