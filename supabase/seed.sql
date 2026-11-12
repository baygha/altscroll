-- Sample feed content so the app has something to scroll before your own
-- dataset arrives. Curated rows have author_id = null and are pre-approved.
--
-- Run after 0001_init.sql:  supabase db execute --file supabase/seed.sql
-- (or paste into the SQL editor).

insert into public.posts
    (author_id, title, body, source_url, topic, tags, status, moderation_model, moderated_at)
values
(null,
 'Why the sky is blue, and sunsets are not',
 'Sunlight contains every visible wavelength. As it passes through the atmosphere it scatters off '
 'nitrogen and oxygen molecules far smaller than the light''s own wavelength — Rayleigh scattering, '
 'whose strength rises with the inverse fourth power of wavelength. Blue light, at roughly 450 nm, '
 'scatters about five times more strongly than red at 700 nm, so blue arrives at your eye from every '
 'direction in the sky. At sunset the light travels a far longer path through the atmosphere, and so '
 'much blue has been scattered away before reaching you that mostly red and orange survive the trip.',
 'https://en.wikipedia.org/wiki/Rayleigh_scattering',
 'physics', array['optics','atmosphere','light'], 'approved', 'seed', now()),

(null,
 'Your gut bacteria outnumber your own cells (almost)',
 'The old claim was ten bacterial cells for every human cell. A 2016 recount put the real ratio near '
 '1.3 to 1 — roughly 38 trillion bacteria against 30 trillion human cells, most of the human count '
 'being red blood cells. The revision did not make the microbiome less important. These organisms '
 'synthesise vitamin K and several B vitamins, ferment fibre into short-chain fatty acids that feed '
 'the cells lining your colon, and train the immune system to distinguish threat from harmless guest.',
 'https://journals.plos.org/plosbiology/article?id=10.1371/journal.pbio.1002533',
 'biology', array['microbiome','immunology','human-body'], 'approved', 'seed', now()),

(null,
 'Why binary search is harder to write than it looks',
 'Jon Bentley reported that 90% of professional programmers failed to write a correct binary search '
 'when given two hours. The classic bug survived in the Java standard library until 2006: computing '
 'the midpoint as (low + high) / 2 overflows a 32-bit signed integer once the array exceeds about a '
 'billion elements, producing a negative index. The fix is low + (high - low) / 2, which never '
 'exceeds the range of either endpoint. The lesson generalises: the dangerous part of an algorithm '
 'is rarely the idea, it is the arithmetic at the boundaries.',
 'https://research.google/blog/extra-extra-read-all-about-it-nearly-all-binary-searches-and-mergesorts-are-broken/',
 'computing', array['algorithms','bugs','integer-overflow'], 'approved', 'seed', now()),

(null,
 'The ocean has absorbed over 90% of excess heat from warming',
 'Greenhouse gases trap energy, but the atmosphere stores remarkably little of it. Water''s heat '
 'capacity is about four times that of air by mass, and the ocean is some 270 times more massive, so '
 'more than 90% of the extra energy accumulated since 1970 has gone into seawater. This is why ocean '
 'heat content is a steadier climate indicator than surface air temperature, which swings with El '
 'Niño. It also explains thermal inertia: even if emissions stopped today, heat already stored at '
 'depth would keep sea level rising through thermal expansion for centuries.',
 'https://www.ipcc.ch/report/ar6/wg1/',
 'climate', array['oceanography','climate-change','thermodynamics'], 'approved', 'seed', now()),

(null,
 'How mRNA vaccines actually work',
 'A conventional vaccine delivers a weakened pathogen or a purified protein. An mRNA vaccine delivers '
 'instructions instead: a strand of messenger RNA encoding one viral protein, wrapped in a lipid '
 'nanoparticle that fuses with your cell membrane. Ribosomes read the strand and build that protein, '
 'the immune system encounters it and builds antibodies, and the mRNA degrades within days — it never '
 'enters the nucleus and cannot alter DNA. The approach was studied for roughly three decades before '
 '2020; the key unlock was Karikó and Weissman''s 2005 discovery that swapping in modified nucleosides '
 'stops the body destroying the strand on contact.',
 'https://www.nobelprize.org/prizes/medicine/2023/press-release/',
 'medicine', array['immunology','vaccines','molecular-biology'], 'approved', 'seed', now()),

(null,
 'Pigeons can distinguish Picasso from Monet',
 'In a 1995 study, Watanabe and colleagues trained pigeons to peck at paintings by one artist and not '
 'the other, rewarding correct choices with food. The birds learned the discrimination, which is not '
 'surprising — but they then generalised correctly to paintings by each artist they had never seen '
 'before, and extended the Monet category to other Impressionists and the Picasso category to other '
 'Cubists. That indicates they extracted abstract stylistic features rather than memorising images. '
 'The work won an Ig Nobel Prize, and is still cited seriously in comparative cognition.',
 'https://pubmed.ncbi.nlm.nih.gov/16812755/',
 'psychology', array['animal-cognition','perception','learning'], 'approved', 'seed', now());
