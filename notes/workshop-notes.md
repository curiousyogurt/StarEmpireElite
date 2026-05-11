# Workshop 2
## Links
  - [Repository](https://github.com/curiousyogurt/StarEmpireElite/tree/main)
  - [Combat Code](https://github.com/curiousyogurt/StarEmpireElite/blob/main/src/com/star_empire_elite/combat.clj)
  - [Simulation in Racket](https://github.com/curiousyogurt/StarEmpireElite/tree/main/simulate)
## What kind of battle system does our code create?
- When one player attacks another, what does the game need to decide?
  - Who wins? (How do we work this out?)
  - What are the losses?
  - What are the gains?
  - What tools (=tech tree) are we going to use? (qualifications)
  - How much randomness should there be?
  - Should defenders have an advantage?
  - Should different unit types matter?
- Current Model
  - How many forces from each side can actually fight?
    - Soldiers
      - Bottlenecked by transports and generals
      - 2000 soldiers, 1 transport = 100 solders
    - Fighters
      - Bottlenecked by carriers and admirals
    - Defence Stations
      - Only defenders
  - Defenders are treated differently
    - soldiers/fighters don't need transports/carriers
    - have defence stations
  - resolution
    - converts everything into *power*
    - each side gets a small random multiplier
    - higher modified power wins
## Does this feel "right"?
  - just numbers - could we incorporate a tech tree?
  - should there be units that are better against other units?
  - do we think it models what battle should look like?
## What are the advantages and disadvantages of our one-big-number system?
  - Current
    - Super simple
    - Asynchronous
    - No story
  - Round-based combat
    - More dramatic (story) 
    - More difficult to calculate and balance
  - Remove randomness
    - Super easy to explain
    - Super easy to calculate
    - Easier to balance
    - Fun? Certainly more chess-like
