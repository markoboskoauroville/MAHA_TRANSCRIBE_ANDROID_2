# R8 changes the program: it removes, inlines and renames. Nothing here is reached by reflection
# or by a string name, so there is nothing to keep, and an empty file is the honest answer rather
# than a defensive -keep that would quietly hold dead code alive with a note saying "spare this".
