(() => {
  const easing = value => {
    const x = Math.max(0, Math.min(1, value));
    let low = 0;
    let high = 1;
    let t = x;
    for (let index = 0; index < 20; index += 1) {
      const inverse = 1 - t;
      const position = 3 * inverse * inverse * t * .333 + 3 * inverse * t * t * .667 + t * t * t;
      if (Math.abs(position - x) < 1e-7) break;
      if (position < x) low = t;
      else high = t;
      t = (low + high) / 2;
    }
    return 3 * (1 - t) * t * t + t * t * t;
  };

  window.createLoadingIndicator = canvas => {
    const context = canvas.getContext("2d");
    let colors = ["#2f6fed"];
    let active = false;
    let animation = null;
    let startedAt = null;

    const draw = timestamp => {
      if (startedAt === null) startedAt = timestamp;
      const width = canvas.clientWidth;
      const height = canvas.clientHeight;
      const density = window.devicePixelRatio || 1;
      const pixelWidth = Math.round(width * density);
      const pixelHeight = Math.round(height * density);
      if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
        canvas.width = pixelWidth;
        canvas.height = pixelHeight;
      }
      context.setTransform(density, 0, 0, density, 0, 0);
      context.clearRect(0, 0, width, height);
      const frame = ((timestamp - startedAt) % 2000) / 2000 * 60;
      const start = 99 * easing((frame - 10) / 50);
      const end = 1 + 99 * easing(frame / 50);
      const scale = Math.min(width, height) * .75 / 350;
      const startAngle = (-90 + frame / 60 * 363 + start * 3.6) * Math.PI / 180;
      const sweepAngle = (end - start) * 3.6 * Math.PI / 180;
      let brush = colors[0];
      if (colors.length > 1) {
        brush = context.createLinearGradient(0, 0, width, height);
        colors.forEach((color, index) => brush.addColorStop(index / (colors.length - 1), color));
      }
      context.strokeStyle = brush;
      context.lineWidth = 50 * scale;
      context.lineCap = "round";
      context.beginPath();
      context.arc(width / 2, height / 2, 150 * scale, startAngle, startAngle + sweepAngle);
      context.stroke();
      animation = window.requestAnimationFrame(draw);
    };

    const updateAnimation = () => {
      if (active && !document.hidden) {
        if (animation === null) animation = window.requestAnimationFrame(draw);
      } else {
        window.cancelAnimationFrame(animation);
        animation = null;
        startedAt = null;
      }
    };

    document.addEventListener("visibilitychange", updateAnimation);

    return {
      setActive(value) {
        active = value;
        updateAnimation();
      },
      setColors(value) {
        colors = value;
      },
    };
  };
})();
