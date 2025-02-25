module Runner {

    import ecstasy.mgmt.*;

    import ecstasy.reflect.ModuleTemplate;

    @Inject Console console;

    void run(String[] modules=[]) {
        Tuple<FutureVar, ConsoleBuffer>?[] results =
            new Array(modules.size, i -> loadAndRun(modules[i]));
        reportResults(results, 0);
    }

    void reportResults(Tuple<FutureVar, ConsoleBuffer>?[] results, Int index) {
        while (index < results.size) {
            Tuple<FutureVar, ConsoleBuffer>? resultTuple = results[index++];
            if (resultTuple != Null) {
                resultTuple[0].whenComplete((_, e) -> {
                    if (e == Null) {
                        console.print(resultTuple[1].backService.toString());
                    } else {
                        console.print(e);
                    }
                    reportResults(results, index);
                });
                return;
            }
        }
    }

    Tuple<FutureVar, ConsoleBuffer>? loadAndRun(String moduleName) {
        @Inject("repository") ModuleRepository repository;

        try {
            ModuleTemplate   template = repository.getResolvedModule(moduleName);
            ConsoleBuffer    buffer   = new ConsoleBuffer();
            ResourceProvider injector = new ModuleResourceProvider(&buffer.maskAs(Console));

            Container container =
                new Container(template, Lightweight, repository, injector);

            buffer.print($"++++++ Loading module: {moduleName} +++++++\n");

            @Future Tuple result = container.invoke("run", Tuple:());
            return (&result, buffer);
        } catch (Exception e) {
            console.print($"Failed to run module {moduleName.quoted()}: {e.text}");
            return Null;
        }
    }

    const ConsoleBuffer
            implements Console {
        ConsoleBack backService = new ConsoleBack();

        @Override
        void print(Object object = "", Boolean suppressNewline = False) {
            backService.print(object.toString(), suppressNewline);
        }

        @Override
        String readLine(Boolean suppressEcho = False) {
            throw new UnsupportedOperation();
        }
    }

    service ConsoleBack {
        private StringBuffer buffer = new StringBuffer();

        void print(Object object = "", Boolean suppressNewline = False) {
            buffer.addAll(object.toString());
            if (!suppressNewline) {
                buffer.add('\n');
            }
        }

        @Override
        String toString() {
            return buffer.toString();
        }
    }

    service ModuleResourceProvider(Console console)
            extends BasicResourceProvider {
        @Override
        Supplier getResource(Type type, String name) {
            switch (type) {
            case Console:
                if (name == "console") {
                    return console;
                    }
                break;

            case FileStore:
                if (name == "storage")
                    {
                    @Inject FileStore storage;
                    return storage;
                    }
                break;

            case Directory:
                switch (name) {
                case "rootDir":
                    @Inject Directory rootDir;
                    return rootDir;

                case "homeDir":
                    @Inject Directory homeDir;
                    return homeDir;

                case "curDir":
                    @Inject Directory curDir;
                    return curDir;

                case "tmpDir":
                    @Inject Directory tmpDir;
                    return tmpDir;
                }
                break;
            }
            return super(type, name);
        }
    }
}