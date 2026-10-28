<script setup>
defineProps({ name: String, size: { type: [Number, String], default: 20 } });
const paths = {
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  jobs: 'M8 5H5a2 2 0 0 0-2 2v13a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1V7a2 2 0 0 0-2-2h-3 M8 3h8v5H8z M8 12h8 M8 16h5',
  clock: 'M12 8v4l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  play: 'm9 5 11 7-11 7z', pause: 'M8 5v14 M16 5v14',
  plus: 'M12 5v14 M5 12h14', arrow: 'M5 12h14 m-5-5 5 5-5 5',
  chevron: 'm9 5 7 7-7 7', search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  refresh: 'M20 7v5h-5 M4 17v-5h5 M6.1 6.1a8 8 0 0 1 13.4 3 M4.5 14.9a8 8 0 0 0 13.4 3',
  check: 'm5 12 4 4L19 6', close: 'm6 6 12 12 M6 18 18 6',
  edit: 'm15 5 4 4 M4 20l4-1L21 6l-4-4L4 15z',
  trash: 'M3 6h18 M9 6V3h6v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7',
  server: 'M3 3h18v7H3z M3 14h18v7H3z M7 6.5h.01 M7 17.5h.01 M11 6.5h6 M11 17.5h6',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
  code: 'm8 7-5 5 5 5 M16 7l5 5-5 5 m-3-14-2 18',
  info: 'M12 11v6 M12 7h.01 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  link: 'm9 15 6-6 M8 16l-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0 m0 12a4 4 0 0 0 6 0l5-5a4 4 0 0 0-6-6l-1 1',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  bolt: 'm13 2-9 12h7l-1 8 10-12h-7z',
};
</script>
<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.jobs" /></svg>
</template>
