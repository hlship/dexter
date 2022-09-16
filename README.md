# dex

 **D**ependency **Ex**plorer.
 
Graphical and interactive way to explore the dependencies
of a project.

# Dev Notes

- [Shadow CLJs](https://github.com/thheller/shadow-cljs)
- [re-frame](https://day8.github.io/re-frame/re-frame/)
- [Reagent](https://github.com/reagent-project/reagent)
- [React Flow](https://reactflow.dev/)
- [React](https://reactjs.org/)
- [ClojureScript/Calva](https://blog.agical.se/en/posts/shadow-cljs-clojure-cljurescript-calva-nrepl-basics/)


## Starting the App

> May have to `nvm use node` first to use a more recent version of Node than the Nu toolset uses.

```clojure
shadow-cljs watch frontend
```

Watches for code and other changes, rebuild UI dynamically.

Open the UI at http://localhost:8080
