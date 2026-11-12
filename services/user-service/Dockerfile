# Build context: this folder only (ADR-0016).
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY Directory.Build.props UserService.sln ./
COPY src/UserService/UserService.csproj src/UserService/packages.lock.json src/UserService/
RUN dotnet restore src/UserService/UserService.csproj --locked-mode
COPY src ./src
RUN dotnet publish src/UserService/UserService.csproj -c Release -o /out --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:10.0-alpine
WORKDIR /app
COPY --from=build /out ./
USER app
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=20 --start-period=10s \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/health/live" >/dev/null || exit 1
ENTRYPOINT ["dotnet", "UserService.dll"]
