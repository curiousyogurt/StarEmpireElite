module.exports = {
  content: [
    './src/**/*',
    './resources/**/*',
  ],
  theme: {
    extend: {
      colors: {
        // Game UI chrome
        'game-bg':           '#0e0e0e',  // terminal shell background
        'game-surface':      '#161616',  // topbar, table background
        'game-card':         '#1e1e1e',  // source cards
        'game-header':       '#151f1a',  // table header row
        'game-row':          '#121a18',  // highlighted (key) resource rows
        // Borders and dividers
        'game-border':       '#253530',  // card/table outer border
        'game-divider':      '#1a2820',  // between-row divider
        'game-green-border': '#1e6e44',  // primary green border/accent
        // Green fills
        'game-green-deep':   '#1a3a28',  // active/highlight fill
        'game-green-done':   '#162a1e',  // completed-phase fill
        // Green text shades (complement to green-400 = #4ade80)
        'game-green-muted':  '#7ab88a',  // secondary text, labels
        'game-green-soft':   '#9adaaa',  // tertiary text, subtitles
        'game-green-mid':    '#629171',  // snapshot column labels
        'game-green-dim':    '#4a6a58',  // inactive/disabled text, separators
        'game-green-dark':   '#3a5040',  // affordability-false state
      },
      borderRadius: {
        'game': '3px',  // standard card border-radius
      },
    },
  },
  plugins: [
    require('@tailwindcss/forms'),
  ],
}
