long long ack(long long m, long long n) {
    if (m == 0) return n + 1;
    if (n == 0) return ack(m - 1, 1);
    return ack(m - 1, ack(m, n - 1));
}

void fill_floats(float* buf, long long n) {
    for (long long i = 0; i < n; i++) {
        buf[i] = (float)(i & 1023) * 0.5f;
    }
}
